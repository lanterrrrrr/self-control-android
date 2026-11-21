package com.focusguard.selfcontrol;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class OfflineSpeech {
    private static final String TAG = "FocusOfflineSpeech";
    private static final String ASSET_MODEL = "mandarin_model.zip";
    private static final String MODEL_VERSION = "vosk-model-small-cn-0.22";
    private static final int SAMPLE_RATE = 16_000;
    private static final int CHUNK_SAMPLES = SAMPLE_RATE / 10;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Model model;

    private OfflineSpeech() {}

    static void prepare(Context context, Consumer<Boolean> prepared) {
        Context app = context.getApplicationContext();
        WORKER.execute(() -> {
            boolean ready = true;
            try {
                getModel(app);
            } catch (IOException | RuntimeException | LinkageError error) {
                ready = false;
                Log.w(TAG, "Could not load the bundled Mandarin model", error);
            }
            boolean available = ready;
            MAIN.post(() -> prepared.accept(available));
        });
    }

    static Session start(Context context, Consumer<String> result) {
        Context app = context.getApplicationContext();
        Session session = new Session();
        WORKER.execute(() -> {
            String text = null;
            try {
                if (session.isHeld()) {
                    text = session.record(getModel(app));
                }
            } catch (IOException | JSONException | RuntimeException | LinkageError error) {
                if (!session.isCancelled()) {
                    Log.w(TAG, "Could not finish offline recognition", error);
                }
            }
            String transcript = text;
            MAIN.post(() -> {
                if (!session.isCancelled()) {
                    result.accept(transcript);
                }
            });
        });
        return session;
    }

    private static Model getModel(Context context) throws IOException {
        if (model == null) {
            File modelDir = new File(context.getFilesDir(), MODEL_VERSION);
            File installed = new File(modelDir, ".installed");
            if (!installed.isFile() || !new File(modelDir, "am/final.mdl").isFile()) {
                extractModel(context, modelDir);
            }
            model = new Model(modelDir.getAbsolutePath());
            if (!installed.isFile() && !installed.createNewFile()) {
                model.close();
                model = null;
                throw new IOException("Could not mark speech model installed");
            }
        }
        return model;
    }

    private static void extractModel(Context context, File modelDir) throws IOException {
        File parent = modelDir.getParentFile();
        String prefix = modelDir.getCanonicalPath() + File.separator;
        try (ZipInputStream input = new ZipInputStream(context.getAssets().open(ASSET_MODEL))) {
            byte[] buffer = new byte[64 * 1024];
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                File file = new File(parent, entry.getName());
                if (!file.getCanonicalPath().startsWith(prefix)) throw new IOException("Unexpected speech asset path");
                File directory = file.getParentFile();
                if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create speech model directory");
                try (FileOutputStream output = new FileOutputStream(file)) {
                    int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                }
            }
        }
        if (!new File(modelDir, "am/final.mdl").isFile()) throw new IOException("Incomplete speech model");
    }

    static final class Session {
        private boolean held = true;
        private boolean cancelled;
        private AudioRecord recording;

        synchronized boolean isHeld() {
            return held;
        }

        synchronized boolean isCancelled() {
            return cancelled;
        }

        synchronized void release() {
            held = false;
            stopRecording();
        }

        synchronized void cancel() {
            cancelled = true;
            release();
        }

        @SuppressLint("MissingPermission")
        private String record(Model model) throws IOException, JSONException {
            int minBufferBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (minBufferBytes <= 0) {
                throw new IOException("Microphone format unavailable");
            }
            try (Recognizer recognizer = new Recognizer(model, SAMPLE_RATE)) {
                AudioRecord audio = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                        Math.max(minBufferBytes, CHUNK_SAMPLES * Short.BYTES * 2));
                try {
                    if (!beginRecording(audio)) {
                        return "";
                    }
                    short[] chunk = new short[CHUNK_SAMPLES];
                    StringBuilder transcript = new StringBuilder();
                    while (isHeld()) {
                        int read = audio.read(chunk, 0, chunk.length, AudioRecord.READ_BLOCKING);
                        if (read > 0) {
                            if (recognizer.acceptWaveForm(chunk, read)) {
                                appendText(transcript, recognizer.getResult());
                            }
                        } else if (read < 0 && isHeld()) {
                            throw new IOException("Microphone read failed: " + read);
                        }
                    }
                    if (isCancelled()) {
                        return null;
                    }
                    appendText(transcript, recognizer.getFinalResult());
                    return transcript.toString();
                } finally {
                    synchronized (this) {
                        stopRecording();
                        recording = null;
                        audio.release();
                    }
                }
            }
        }

        private synchronized boolean beginRecording(AudioRecord audio) throws IOException {
            if (!held) {
                return false;
            }
            if (audio.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IOException("Microphone did not initialize");
            }
            recording = audio;
            audio.startRecording();
            if (audio.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IOException("Microphone did not start");
            }
            return true;
        }

        private void stopRecording() {
            if (recording != null && recording.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                try {
                    recording.stop();
                } catch (IllegalStateException ignored) {
                    // The platform already stopped capture.
                }
            }
        }

        private static void appendText(StringBuilder transcript, String json) throws JSONException {
            String text = new JSONObject(json).optString("text").trim();
            if (!text.isEmpty()) {
                transcript.append(text).append(' ');
            }
        }
    }
}
