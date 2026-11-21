package com.focusguard.selfcontrol;

import android.app.AppOpsManager;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

final class AppStore extends SQLiteOpenHelper {
    private static final String TAG = "SelfControlStore";
    private static final long CLOCK_TOLERANCE_MS = 10 * 60_000;
    private static final long MAX_OBSERVATION_GAP_MS = 4_000;
    private static AppStore instance;

    private final Context context;
    private final int boot;
    private final SecureRandom tokens = new SecureRandom();
    private RuleEngine engine = new RuleEngine();
    private ZoneId zone;
    private boolean ready;
    private boolean clockValid;
    private long anchorWall;
    private long anchorElapsed;
    private String activePackage;
    private long activeSample;
    private boolean monitorConnected;
    private long challengeToken;
    private long challengeDay;
    private String challengePackage;

    static synchronized AppStore get(Context context) {
        if (instance == null) instance = new AppStore(context.getApplicationContext());
        return instance;
    }

    private AppStore(Context context) {
        super(context, "regulation.db", null, 1);
        this.context = context;
        boot = Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        zone = ZoneId.systemDefault();
        anchorWall = System.currentTimeMillis();
        anchorElapsed = SystemClock.elapsedRealtime();
        try (Cursor row = getWritableDatabase().rawQuery("SELECT state, zone, boot, wall, elapsed FROM snapshot", null)) {
            if (row.moveToFirst()) {
                engine = RuleEngine.decode(row.getBlob(0));
                zone = ZoneId.of(row.getString(1));
                if (boot >= 0 && boot == row.getInt(2)) {
                    anchorWall = row.getLong(3);
                    anchorElapsed = row.getLong(4);
                }
            }
            ready = true;
            refreshDay(System.currentTimeMillis(), SystemClock.elapsedRealtime());
        } catch (IOException | RuntimeException ex) {
            fail(ex);
        }
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE snapshot (id INTEGER PRIMARY KEY CHECK(id = 1), state BLOB NOT NULL, "
                + "zone TEXT NOT NULL, boot INTEGER NOT NULL, wall INTEGER NOT NULL, elapsed INTEGER NOT NULL)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("Unsupported rules database version " + oldVersion);
    }

    static boolean usageAllowed(Context context) {
        AppOpsManager appOps = context.getSystemService(AppOpsManager.class);
        return appOps != null && appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), context.getPackageName()) == AppOpsManager.MODE_ALLOWED;
    }

    synchronized boolean ready() { return ready; }
    synchronized boolean paused() { return engine.paused(); }
    synchronized boolean monitorConnected() { return monitorConnected; }
    synchronized String timeZone() { return zone.getId(); }

    synchronized void setMonitorConnected(boolean connected) {
        monitorConnected = connected;
        if (!connected && !monitorEnabled()) clearChallenge();
    }

    synchronized boolean clockValid() {
        return refreshDay(System.currentTimeMillis(), SystemClock.elapsedRealtime());
    }

    synchronized long day() {
        clockValid();
        return engine.day();
    }

    synchronized List<RuleEngine.Rule> rules() {
        clockValid();
        return engine.rules();
    }

    synchronized List<RuleEngine.DaySummary> history() {
        clockValid();
        return engine.history();
    }

    synchronized RuleEngine.Decision inspect(String pkg) {
        long now = SystemClock.elapsedRealtime();
        refreshDay(System.currentTimeMillis(), now);
        return engine.inspect(pkg, now, boot);
    }

    synchronized boolean configure(String pkg, Integer minutes) {
        if (!clockValid() || (minutes != null && ProtectedApps.isProtected(context, pkg))) return false;
        try {
            engine.configure(pkg, minutes);
            return save();
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    synchronized void setPaused(boolean paused) {
        foreground(null);
        if (!ready || (engine.paused() == paused)) return;
        engine.setPaused(paused);
        clearChallenge();
        save();
    }

    synchronized void foreground(String pkg) {
        long now = SystemClock.elapsedRealtime();
        long wall = System.currentTimeMillis();
        if (!refreshDay(wall, now) || engine.paused()) pkg = null;
        if (ready && clockValid) settle(now);
        activePackage = ready ? pkg : null;
        activeSample = now;
    }

    synchronized boolean claimGrace(String pkg) {
        if (!canGrant(pkg)) return false;
        return engine.claimGrace(pkg, SystemClock.elapsedRealtime(), boot) && save();
    }

    synchronized int remainingChallenges() {
        if (!clockValid() || engine.paused()) return 0;
        return engine.inspect(context.getPackageName(), SystemClock.elapsedRealtime(), boot).challengesLeft();
    }

    synchronized long beginChallenge(String pkg) {
        if (!canGrant(pkg) || remainingChallenges() == 0
                || engine.inspect(pkg, SystemClock.elapsedRealtime(), boot).phase() != RuleEngine.Phase.BLOCKED) return 0;
        challengeToken = tokens.nextLong() & Long.MAX_VALUE;
        if (challengeToken == 0) challengeToken = 1;
        challengeDay = engine.day();
        challengePackage = pkg;
        return challengeToken;
    }

    synchronized String challengeTarget(long token) {
        if (token <= 0 || token != challengeToken || !canGrant(challengePackage) || engine.day() != challengeDay) return null;
        return challengePackage;
    }

    synchronized boolean completeChallenge(long token) {
        String pkg = challengeTarget(token);
        if (pkg == null) return false;
        long day = challengeDay;
        clearChallenge();
        return engine.grantChallenge(pkg, day, SystemClock.elapsedRealtime(), boot) && save();
    }

    synchronized void cancelChallenge(long token) {
        if (token == challengeToken) clearChallenge();
    }

    private boolean canGrant(String pkg) {
        return pkg != null && monitorEnabled() && clockValid() && !engine.paused() && usageAllowed(context)
                && !ProtectedApps.isProtected(context, pkg);
    }

    boolean monitorEnabled() {
        String services = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (services == null || Settings.Secure.getInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 0) return false;
        ComponentName target = new ComponentName(context, MonitorService.class);
        for (String service : services.split(":")) {
            if (target.equals(ComponentName.unflattenFromString(service))) return true;
        }
        return false;
    }

    private void clearChallenge() {
        challengeToken = 0;
        challengePackage = null;
    }

    private boolean refreshDay(long wall, long now) {
        if (!ready) return false;
        long projected = anchorWall + (now - anchorElapsed);
        long epochDay = Instant.ofEpochMilli(wall).atZone(zone).toLocalDate().toEpochDay();
        if (Math.abs(wall - projected) > CLOCK_TOLERANCE_MS || (engine.day() != Long.MIN_VALUE && epochDay < engine.day())) {
            activePackage = null;
            clearChallenge();
            clockValid = false;
            return false;
        }
        clockValid = true;
        if (engine.day() != epochDay) {
            if (engine.day() != Long.MIN_VALUE) {
                long midnight = LocalDate.ofEpochDay(epochDay).atStartOfDay(zone).toInstant().toEpochMilli();
                if (wall - midnight < MAX_OBSERVATION_GAP_MS) {
                    long boundary = now - Math.max(0L, wall - midnight);
                    settle(boundary);
                    activeSample = Math.max(activeSample, boundary);
                } else {
                    activePackage = null;
                }
            }
            if (!engine.advanceDay(epochDay)) return false;
            clearChallenge();
            return save();
        }
        return true;
    }

    private void settle(long now) {
        if (activePackage == null || now <= activeSample || now - activeSample > MAX_OBSERVATION_GAP_MS) return;
        if (engine.addForeground(activePackage, now - activeSample, now, boot)) save();
        activeSample = now;
    }

    private boolean save() {
        try {
            ContentValues state = new ContentValues();
            state.put("id", 1);
            state.put("state", engine.encode());
            state.put("zone", zone.getId());
            state.put("boot", boot);
            state.put("wall", anchorWall);
            state.put("elapsed", anchorElapsed);
            long inserted = getWritableDatabase().insertWithOnConflict("snapshot", null, state, SQLiteDatabase.CONFLICT_REPLACE);
            if (inserted == -1) throw new IOException("Rules database write failed");
            return true;
        } catch (IOException | RuntimeException ex) {
            fail(ex);
            return false;
        }
    }

    private void fail(Exception ex) {
        ready = false;
        clockValid = false;
        activePackage = null;
        clearChallenge();
        Log.w(TAG, "Regulation paused because the rules database is unavailable", ex);
    }
}
