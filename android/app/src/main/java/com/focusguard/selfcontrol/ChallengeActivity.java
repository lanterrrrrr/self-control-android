package com.focusguard.selfcontrol;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.icu.text.Transliterator;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

public final class ChallengeActivity extends Activity {
    private static final int BACKGROUND = 0xfff4f7f3;
    private static final int INK = 0xff1a302a;
    private static final int MUTED_INK = 0xff53675d;
    private static final int ACCENT = 0xff20604a;
    private static final int SOFT_ACCENT = 0xffe2ece5;
    private static final int BORDER = 0xffcedbd1;
    private static final int ERROR = 0xff973a2d;
    private static final int MIC_REQUEST = 100;
    private static final int VOICE_WINDOW_MS = 16_000;

    private enum Mode {
        VOICE("朗读"), TYPING("打字"), MATH("算术题");

        private final String title;

        Mode(String title) {
            this.title = title;
        }
    }

    private enum BundledVoice {
        UNCHECKED, PREPARING, READY, FAILED
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable voiceTimeout = () -> {
        cancelVoice();
        showStatus("这次识别已超时。可以重新朗读或切换方式。", true);
    };

    private AppStore store;
    private long token;
    private String target;
    private Mode mode = Mode.VOICE;
    private int quoteIndex = -1;
    private ChallengeText.Quote quote;
    private List<ChallengeText.Quote> typingQuotes = List.of();
    private ChallengeText.MathQuestion math;
    private String typingDraft = "";
    private String mathDraft = "";
    private String typingExpected = "";
    private Transliterator toSimplified;
    private LinearLayout challengeContent;
    private final Button[] modeButtons = new Button[Mode.values().length];
    private TextView status;
    private Button voiceButton;
    private EditText input;
    private boolean microphoneRequestPending;
    private OfflineSpeech.Session offlineSession;
    private BundledVoice bundledVoice = BundledVoice.UNCHECKED;
    private long voiceGeneration;
    private boolean holding;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        store = AppStore.get(this);
        token = getIntent().getLongExtra("token", 0);
        target = store.challengeTarget(token);
        if (target == null) {
            expire();
            return;
        }

        if (saved != null) {
            mode = Mode.valueOf(saved.getString("mode", Mode.VOICE.name()));
            quoteIndex = saved.getInt("quoteIndex", -1);
        }
        loadChallenge();
        buildPage();
        renderMode();
    }

    private void loadChallenge() {
        math = ChallengeText.randomMath(new Random(token));
        try (InputStreamReader reader = new InputStreamReader(
                getAssets().open("quotes.tsv"), StandardCharsets.UTF_8)) {
            List<ChallengeText.Quote> quotes = ChallengeText.loadQuotes(reader);
            if (quoteIndex < 0 || quoteIndex >= quotes.size()) {
                SharedPreferences history = getPreferences(MODE_PRIVATE);
                int last = history.getInt("lastQuote", -1);
                Random random = new Random();
                do {
                    quoteIndex = random.nextInt(quotes.size());
                } while (quoteIndex == last);
                history.edit().putInt("lastQuote", quoteIndex).apply();
            }
            quote = quotes.get(quoteIndex);
            typingQuotes = ChallengeText.typingQuotes(quotes, quote, new Random(token));
            typingExpected = typingQuotes.stream().map(ChallengeText.Quote::text).collect(Collectors.joining());
        } catch (IOException error) {
            mode = Mode.MATH;
        }
    }

    private void buildPage() {
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BACKGROUND);
        scroll.setClipToPadding(false);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bounds = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            int keyboard = insets.getInsets(WindowInsets.Type.ime()).bottom;
            view.setPadding(bounds.left, bounds.top, bounds.right, Math.max(bounds.bottom, keyboard));
            return insets;
        });

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(24), dp(18), dp(24), dp(24));
        page.setFocusableInTouchMode(true);
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        setContentView(scroll);
        WindowInsetsController controller = getWindow().getDecorView().getWindowInsetsController();
        if (controller != null) {
            int lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            controller.setSystemBarsAppearance(lightBars, lightBars);
        }
        scroll.requestApplyInsets();

        TextView heading = label("继续使用前，请完成挑战", 28, INK, true);
        heading.setAccessibilityHeading(true);
        add(page, heading, 0);
        add(page, label(ProtectedApps.label(this, target)
                + " · 今日剩余 " + store.remainingChallenges() + " 次\n通过后可再使用 5 分钟。", 15, MUTED_INK, false), 9);

        LinearLayout options = new LinearLayout(this);
        options.setOrientation(LinearLayout.HORIZONTAL);
        for (Mode option : Mode.values()) {
            Button button = new Button(this);
            modeButtons[option.ordinal()] = button;
            configureButton(button, option.title);
            button.setOnClickListener(view -> switchMode(option));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
            if (option != Mode.VOICE) {
                params.leftMargin = dp(6);
            }
            options.addView(button, params);
        }
        add(page, options, 24);

        challengeContent = new LinearLayout(this);
        challengeContent.setOrientation(LinearLayout.VERTICAL);
        add(page, challengeContent, 22);

        status = label("", 14, MUTED_INK, false);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        add(page, status, 12);

        Button leave = button("离开应用", false);
        leave.setOnClickListener(view -> leaveForHome());
        add(page, leave, 18);
        page.requestFocus();
    }

    private void switchMode(Mode option) {
        if (mode == option) {
            return;
        }
        saveDraft();
        if (input != null) {
            getSystemService(InputMethodManager.class).hideSoftInputFromWindow(input.getWindowToken(), 0);
        }
        cancelVoice();
        mode = option;
        renderMode();
    }

    private void renderMode() {
        for (Mode option : Mode.values()) {
            Button button = modeButtons[option.ordinal()];
            button.setSelected(option == mode);
            button.setEnabled(option == Mode.MATH || quote != null);
            styleButton(button, option == mode);
        }
        challengeContent.removeAllViews();
        voiceButton = null;
        input = null;
        showStatus("", false);
        switch (mode) {
            case VOICE -> renderVoice();
            case TYPING -> renderTyping();
            case MATH -> renderMath();
        }
        if (quote == null) {
            showStatus("词库暂不可用。你仍可以用算术题完成挑战。", true);
        }
    }

    private void renderVoice() {
        add(challengeContent, label(quote.text(), 25, INK, true), 0);
        if ("公版".equals(quote.rights())) {
            add(challengeContent, label(quote.source(), 13, MUTED_INK, false), 7);
        }
        add(challengeContent, label("读完整句，松手后校验。语音在设备上识别。", 15, MUTED_INK, false), 10);
        voiceButton = button("按住朗读", true);
        voiceButton.setOnClickListener(view -> {
            if (offlineSession == null) {
                showStatus("按住按钮朗读，完成后再松手。", false);
            }
        });
        voiceButton.setOnTouchListener((view, event) -> {
            int touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    view.setPressed(true);
                    beginVoice();
                }
                case MotionEvent.ACTION_UP -> {
                    view.setPressed(false);
                    releaseVoice();
                    if (offlineSession == null && !microphoneRequestPending) {
                        view.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED);
                    }
                }
                case MotionEvent.ACTION_MOVE -> {
                    if (view.isPressed() && (event.getX() < -touchSlop
                            || event.getX() > view.getWidth() + touchSlop
                            || event.getY() < -touchSlop
                            || event.getY() > view.getHeight() + touchSlop)) {
                        cancelVoice();
                        showStatus("录音已取消。可以重新朗读。", false);
                    }
                }
                case MotionEvent.ACTION_CANCEL -> {
                    view.setPressed(false);
                    cancelVoice();
                    showStatus("录音已取消。可以重新朗读。", false);
                }
                default -> { }
            }
            return true;
        });
        voiceButton.setOnKeyListener((view, key, event) -> {
            if (key != KeyEvent.KEYCODE_ENTER && key != KeyEvent.KEYCODE_SPACE
                    && key != KeyEvent.KEYCODE_DPAD_CENTER) {
                return false;
            }
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                beginVoice();
            }
            if (event.getAction() == KeyEvent.ACTION_UP) {
                if (event.isCanceled()) {
                    cancelVoice();
                } else {
                    releaseVoice();
                }
            }
            return true;
        });
        add(challengeContent, voiceButton, 16);
        prepareBundledVoice();
    }

    private void renderTyping() {
        add(challengeContent, label("按顺序输入下面三句话。标点和空格可省略。", 15, MUTED_INK, false), 0);
        for (ChallengeText.Quote line : typingQuotes) {
            add(challengeContent, label(line.text(), 19, INK, true), 7);
        }
        input = answerField("输入三句话", typingDraft);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setMinLines(3);
        input.setMaxLines(6);
        add(challengeContent, input, 12);
        Button submit = button("核对文字", true);
        submit.setOnClickListener(view -> checkAnswer());
        add(challengeContent, submit, 12);
    }

    private void renderMath() {
        add(challengeContent, label(math.prompt(), 32, INK, true), 0);
        input = answerField("输入整数答案", mathDraft);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setOnEditorActionListener((field, action, event) -> {
            if (action == EditorInfo.IME_ACTION_DONE || event != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_UP) {
                checkAnswer();
                return true;
            }
            return false;
        });
        add(challengeContent, input, 12);
        Button submit = button("核对答案", true);
        submit.setOnClickListener(view -> checkAnswer());
        add(challengeContent, submit, 12);
    }

    private EditText answerField(String hint, String draft) {
        EditText field = new EditText(this);
        field.setTextColor(INK);
        field.setHintTextColor(MUTED_INK);
        field.setTextSize(17);
        field.setHint(hint);
        field.setText(draft);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(240)});
        field.setBackgroundTintList(ColorStateList.valueOf(ACCENT));
        return field;
    }

    private void checkAnswer() {
        if (input == null || isFinishing()) {
            return;
        }
        String answer = input.getText().toString();
        boolean valid = mode == Mode.MATH ? math.passes(answer)
                : ChallengeText.typingPasses(typingExpected, simplified(answer));
        if (valid) {
            complete();
        } else {
            showStatus(mode == Mode.MATH ? "答案不正确。可以重试或切换方式。"
                    : "文字有出入。核对各句后重试，也可以切换方式。", true);
        }
    }

    private void prepareBundledVoice() {
        if (bundledVoice == BundledVoice.PREPARING) {
            showStatus("正在准备应用内普通话模型。", false);
            return;
        }
        if (bundledVoice == BundledVoice.FAILED) {
            showStatus("应用内语音模型暂不可用。可以用打字或算术题继续。", true);
            return;
        }
        if (bundledVoice == BundledVoice.READY) {
            return;
        }
        bundledVoice = BundledVoice.PREPARING;
        showStatus("正在准备应用内普通话模型。", false);
        OfflineSpeech.prepare(this, ready -> {
            bundledVoice = ready ? BundledVoice.READY : BundledVoice.FAILED;
            if (mode == Mode.VOICE && !isFinishing() && !isDestroyed()) {
                showStatus(ready ? "普通话离线识别已就绪，可以按住朗读。"
                        : "应用内语音模型暂不可用。可以用打字或算术题继续。", !ready);
            }
        });
    }

    private void beginVoice() {
        if (offlineSession != null || microphoneRequestPending || isFinishing()) {
            return;
        }
        if (bundledVoice != BundledVoice.READY) {
            prepareBundledVoice();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            microphoneRequestPending = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC_REQUEST);
            return;
        }

        long generation = ++voiceGeneration;
        holding = true;
        voiceButton.setText("松手校验");
        showStatus("正在听，请读完整句。", false);
        mainHandler.postDelayed(voiceTimeout, VOICE_WINDOW_MS);
        offlineSession = OfflineSpeech.start(this, recognized -> {
            if (generation != voiceGeneration || mode != Mode.VOICE || isFinishing()) {
                return;
            }
            offlineSession = null;
            cancelVoice();
            verifyVoice(recognized);
        });
    }

    private void releaseVoice() {
        if (!holding || offlineSession == null) {
            return;
        }
        holding = false;
        voiceButton.setText("按住朗读");
        offlineSession.release();
        showStatus("正在校验朗读，请稍等。", false);
    }

    private void verifyVoice(String recognized) {
        if (recognized == null) {
            showStatus("麦克风或离线识别暂不可用。可以重试或切换方式。", true);
        } else if (recognized.trim().isEmpty()) {
            showStatus("没有识别到完整文字。可以重新朗读或切换方式。", true);
        } else if (ChallengeText.voicePasses(quote.text(), simplified(recognized))) {
            complete();
        } else {
            showStatus("朗读与原句的匹配度低于 70%。可以重试或切换方式。\n本次识别为“"
                    + recognized.trim().replace(" ", "") + "”", true);
        }
    }

    private void cancelVoice() {
        ++voiceGeneration;
        mainHandler.removeCallbacks(voiceTimeout);
        holding = false;
        if (offlineSession != null) {
            offlineSession.cancel();
            offlineSession = null;
        }
        if (voiceButton != null) {
            voiceButton.setText("按住朗读");
            voiceButton.setPressed(false);
        }
    }

    private String simplified(String text) {
        if (toSimplified == null) {
            toSimplified = Transliterator.getInstance("Traditional-Simplified");
        }
        return toSimplified.transliterate(text);
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request != MIC_REQUEST) {
            return;
        }
        microphoneRequestPending = false;
        if (mode == Mode.VOICE && !isFinishing()) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                showStatus("麦克风已授权。请重新按住按钮朗读。", false);
            } else {
                showStatus("麦克风未授权。可以用打字或算术题继续。", true);
            }
        }
    }

    private void complete() {
        if (token == 0 || isFinishing()) {
            return;
        }
        if (!store.completeChallenge(token)) {
            expire();
            return;
        }
        token = 0;
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage(target);
            if (launch == null) {
                throw new ActivityNotFoundException();
            }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(launch);
        } catch (ActivityNotFoundException | SecurityException error) {
            Toast.makeText(this, "已保留 5 分钟。请从桌面重新打开应用。", Toast.LENGTH_LONG).show();
        }
        finish();
    }

    private void expire() {
        Toast.makeText(this, "挑战已经结束，请重新打开受管应用。", Toast.LENGTH_SHORT).show();
        closeChallenge();
    }

    private void leaveForHome() {
        try {
            startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException ignored) {
            // System navigation remains available when the device has no accessible Home activity.
        }
        closeChallenge();
    }

    private void closeChallenge() {
        cancelVoice();
        if (token != 0) {
            store.cancelChallenge(token);
            token = 0;
        }
        finish();
    }

    private void saveDraft() {
        if (input == null) {
            return;
        }
        if (mode == Mode.TYPING) {
            typingDraft = input.getText().toString();
        } else if (mode == Mode.MATH) {
            mathDraft = input.getText().toString();
        }
    }

    @Override
    public void onBackPressed() {
        leaveForHome();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("mode", mode.name());
        out.putInt("quoteIndex", quoteIndex);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (offlineSession != null) {
            cancelVoice();
            showStatus("录音已结束。有需要时可以重新朗读。", false);
        } else if (mode == Mode.VOICE) {
            showStatus("", false);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (token != 0 && !isChangingConfigurations() && !microphoneRequestPending) {
            closeChallenge();
        }
    }

    @Override
    protected void onDestroy() {
        cancelVoice();
        super.onDestroy();
    }

    private void showStatus(String message, boolean error) {
        if (status != null) {
            status.setTextColor(error ? ERROR : MUTED_INK);
            status.setText(message);
            status.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    private TextView label(String value, int sp, int color, boolean bold) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(sp);
        text.setTextColor(color);
        text.setLineSpacing(dp(3), 1);
        if (bold) {
            text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        return text;
    }

    private Button button(String title, boolean primary) {
        Button button = new Button(this);
        configureButton(button, title);
        styleButton(button, primary);
        return button;
    }

    private void configureButton(Button button, String title) {
        button.setText(title);
        button.setTextSize(15);
        button.setAllCaps(false);
        button.setMinimumWidth(0);
        button.setMinWidth(0);
        button.setMinimumHeight(dp(48));
        button.setPadding(dp(6), dp(9), dp(6), dp(9));
    }

    private void styleButton(Button button, boolean primary) {
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(12));
        if (!button.isEnabled()) {
            shape.setColor(BACKGROUND);
            button.setTextColor(MUTED_INK);
        } else {
            shape.setColor(primary ? ACCENT : SOFT_ACCENT);
            if (!primary) {
                shape.setStroke(dp(1), BORDER);
            }
            button.setTextColor(primary ? Color.WHITE : INK);
        }
        button.setBackgroundTintList(null);
        button.setBackground(new RippleDrawable(
                ColorStateList.valueOf(primary ? 0x55ffffff : 0x3320604a), shape, null));
    }

    private void add(LinearLayout layout, View child, int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(topMargin);
        layout.addView(child, params);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
