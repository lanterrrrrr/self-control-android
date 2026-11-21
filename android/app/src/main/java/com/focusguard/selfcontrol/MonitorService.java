package com.focusguard.selfcontrol;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.util.SparseArray;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;
import java.util.Set;

public final class MonitorService extends AccessibilityService {
    private static final String TAG = "FocusGuardMonitor";
    private static final long TICK_MS = 500;
    private static final long PROTECTED_REFRESH_MS = 5_000;
    private static final long USAGE_REFRESH_MS = 1_000;
    private static final long RECENT_USAGE_MS = 2_000;
    private static final int BACKGROUND = Color.rgb(244, 247, 243);
    private static final int TEXT = Color.rgb(24, 51, 42);
    private static final int ACTION = Color.rgb(32, 96, 74);
    private static final int WINDOW_CACHE_LIMIT = 256;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SparseArray<String> windowOwners = new SparseArray<>();
    private final Runnable reevaluate = this::evaluate;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (connected) {
                evaluate();
                handler.postDelayed(this, TICK_MS);
            }
        }
    };
    private final BroadcastReceiver screenChanges = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                stopObservation();
            } else {
                scheduleEvaluation();
            }
        }
    };

    private AppStore store;
    private KeyguardManager keyguard;
    private PowerManager power;
    private UsageStatsManager usageStats;
    private WindowManager windows;
    private boolean connected;
    private boolean receiverRegistered;
    private Set<String> protectedPackages = Set.of();
    private long protectedRefreshAt;
    private String recentResumedPackage;
    private long recentResumedAt;
    private long usageRefreshAt;

    private ScrollView overlay;
    private String overlayPackage;
    private TextView overlayStatus;
    private Button graceButton;
    private Button challengeButton;

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        if (connected) {
            return;
        }
        try {
            store = AppStore.get(this);
            keyguard = getSystemService(KeyguardManager.class);
            power = getSystemService(PowerManager.class);
            usageStats = getSystemService(UsageStatsManager.class);
            windows = getSystemService(WindowManager.class);
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_USER_PRESENT);
            filter.addAction(Intent.ACTION_DATE_CHANGED);
            filter.addAction(Intent.ACTION_TIME_CHANGED);
            filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(screenChanges, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(screenChanges, filter);
            }
            receiverRegistered = true;
            connected = true;
            store.setMonitorConnected(true);
            handler.post(tick);
        } catch (RuntimeException exception) {
            stopObservation();
            Log.w(TAG, "Could not initialize monitoring", exception);
        }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!connected) {
            return;
        }
        int windowId = event.getWindowId();
        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && windowOwners.get(windowId) != null) return;
            CharSequence packageName = event.getPackageName();
            if (windowId >= 0 && packageName != null) {
                if (windowOwners.size() >= WINDOW_CACHE_LIMIT) windowOwners.removeAt(0);
                windowOwners.put(windowId, packageName.toString());
            }
        } else if (type != AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            return;
        }
        scheduleEvaluation();
    }

    @Override public void onInterrupt() {
        stopObservation();
        windowOwners.clear();
    }

    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        removeOverlay();
        scheduleEvaluation();
    }

    @Override public boolean onUnbind(Intent intent) {
        stopMonitoring();
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        stopMonitoring();
        super.onDestroy();
    }

    private void stopMonitoring() {
        connected = false;
        if (store != null) store.setMonitorConnected(false);
        handler.removeCallbacks(tick);
        handler.removeCallbacks(reevaluate);
        if (receiverRegistered) {
            receiverRegistered = false;
            unregisterReceiver(screenChanges);
        }
        stopObservation();
        windowOwners.clear();
    }

    private void scheduleEvaluation() {
        handler.removeCallbacks(reevaluate);
        handler.post(reevaluate);
    }

    private void evaluate() {
        if (!connected) {
            return;
        }
        try {
            String packageName = monitoringAvailable() ? foregroundPackage() : null;
            if (packageName == null || isProtected(packageName) || recentUsageDisagrees(packageName)) {
                stopObservation();
                return;
            }
            store.foreground(packageName);
            RuleEngine.Decision decision = store.inspect(packageName);
            if (!store.clockValid() || decision.phase() != RuleEngine.Phase.BLOCKED) {
                removeOverlay();
                return;
            }
            showOverlay(packageName, decision);
        } catch (RuntimeException exception) {
            stopObservation();
            Log.w(TAG, "Monitoring paused after a platform error", exception);
        }
    }

    private boolean monitoringAvailable() {
        return store != null && store.ready() && store.clockValid() && !store.paused()
                && AppStore.usageAllowed(this) && keyguard != null && power != null && usageStats != null
                && windows != null && power.isInteractive() && !keyguard.isKeyguardLocked();
    }

    private boolean isProtected(String packageName) {
        long now = SystemClock.elapsedRealtime();
        if (now >= protectedRefreshAt) {
            protectedPackages = ProtectedApps.packageNames(this);
            protectedRefreshAt = now + PROTECTED_REFRESH_MS;
        }
        return protectedPackages.contains(packageName);
    }

    private boolean recentUsageDisagrees(String packageName) {
        long elapsed = SystemClock.elapsedRealtime();
        long wall = System.currentTimeMillis();
        if (elapsed >= usageRefreshAt) {
            usageRefreshAt = elapsed + USAGE_REFRESH_MS;
            recentResumedPackage = null;
            UsageEvents events = usageStats.queryEvents(wall - RECENT_USAGE_MS, wall + 1);
            if (events != null) {
                UsageEvents.Event event = new UsageEvents.Event();
                while (events.getNextEvent(event)) {
                    if (event.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) {
                        recentResumedPackage = event.getPackageName();
                        recentResumedAt = event.getTimeStamp();
                    }
                }
            }
        }
        return recentResumedPackage != null && wall - recentResumedAt <= RECENT_USAGE_MS
                && !packageName.equals(recentResumedPackage);
    }

    private String foregroundPackage() {
        List<AccessibilityWindowInfo> current = getWindows();
        try {
            AccessibilityWindowInfo focus = null;
            for (AccessibilityWindowInfo window : current) {
                int type = window.getType();
                if (window.isInPictureInPictureMode()
                        || type == AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER
                        || (type == AccessibilityWindowInfo.TYPE_SYSTEM
                        && (window.isFocused() || window.isActive()))) {
                    return null;
                }
                if (type == AccessibilityWindowInfo.TYPE_APPLICATION && window.isFocused()) {
                    focus = window;
                }
            }
            if (focus == null) {
                return null;
            }
            int family = rootWindowId(focus, current);
            String packageName = windowOwners.get(family);
            if (packageName == null) packageName = windowOwners.get(focus.getId());
            if (packageName == null) return null;
            for (AccessibilityWindowInfo window : current) {
                if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                String owner = windowOwners.get(window.getId());
                if (rootWindowId(window, current) != family) return null;
                if (owner != null && !packageName.equals(owner)) return null;
            }
            return packageName;
        } finally {
            if (Build.VERSION.SDK_INT < 33) {
                for (AccessibilityWindowInfo window : current) {
                    window.recycle();
                }
            }
        }
    }

    private int rootWindowId(AccessibilityWindowInfo window, List<AccessibilityWindowInfo> current) {
        int id = window.getId();
        for (int depth = 0; depth < current.size(); depth++) {
            AccessibilityWindowInfo parent = window.getParent();
            if (parent == null) return id;
            int parentId = parent.getId();
            if (Build.VERSION.SDK_INT < 33) parent.recycle();
            AccessibilityWindowInfo next = null;
            for (AccessibilityWindowInfo candidate : current) {
                if (candidate.getId() == parentId && candidate.getType() == AccessibilityWindowInfo.TYPE_APPLICATION) {
                    next = candidate;
                    break;
                }
            }
            if (next == null || parentId == id) return id;
            id = parentId;
            window = next;
        }
        return id;
    }

    private void stopObservation() {
        removeOverlay();
        if (store != null) {
            try {
                store.foreground(null);
            } catch (RuntimeException exception) {
                Log.w(TAG, "Could not stop the foreground timer", exception);
            }
        }
    }

    private void showOverlay(String packageName, RuleEngine.Decision decision) {
        if (!packageName.equals(overlayPackage)) {
            removeOverlay();
            if (!connected) {
                return;
            }
            buildOverlay(packageName);
        }
        overlayStatus.setText(decision.challengesLeft() > 0
                ? "今日还可成功挑战 " + decision.challengesLeft() + " 次"
                : "今日挑战次数已用完");
        graceButton.setVisibility(decision.graceAvailable() ? View.VISIBLE : View.GONE);
        challengeButton.setVisibility(decision.challengesLeft() > 0 ? View.VISIBLE : View.GONE);
    }

    private void buildOverlay(String packageName) {
        ScrollView layout = new ScrollView(this);
        layout.setFillViewport(true);
        layout.setBackgroundColor(BACKGROUND);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        content.setPadding(dp(28), dp(24), dp(28), dp(24));
        layout.addView(content);

        TextView title = text(ProtectedApps.label(this, packageName) + " 今日用时已达上限", 25);
        title.setAccessibilityHeading(true);
        content.addView(title);
        overlayStatus = text("", 16);
        overlayStatus.setPadding(0, dp(8), 0, dp(20));
        content.addView(overlayStatus);

        graceButton = button(content, "最后 2 分钟", ACTION, () -> claimGrace(packageName));
        challengeButton = button(content, "完成挑战，再用 5 分钟", ACTION,
                () -> launchChallenge(packageName));
        button(content, "返回桌面", ACTION, () -> performGlobalAction(GLOBAL_ACTION_HOME));

        WindowMetrics metrics = windows.getCurrentWindowMetrics();
        Rect bounds = metrics.getBounds();
        Insets insets = metrics.getWindowInsets().getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        int top = Math.max(insets.top, dp(24));
        int bottom = Math.max(insets.bottom, dp(24));
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                Math.max(dp(48), bounds.width() - insets.left - insets.right),
                Math.max(dp(48), bounds.height() - top - bottom),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
                PixelFormat.TRANSLUCENT);
        params.setTitle("自控力应用拦截");
        params.setFitInsetsTypes(0);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.x = insets.left;
        params.y = top;
        overlay = layout;
        overlayPackage = packageName;
        windows.addView(layout, params);
    }

    private TextView text(String value, int size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(TEXT);
        view.setGravity(Gravity.CENTER);
        return view;
    }

    private Button button(LinearLayout parent, String label, int tint, Runnable click) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(16);
        button.setTextColor(Color.WHITE);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(tint));
        button.setMinHeight(dp(48));
        button.setOnClickListener(view -> click.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        parent.addView(button, params);
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private boolean stillBlocking(String packageName) {
        return monitoringAvailable() && packageName.equals(overlayPackage)
                && packageName.equals(foregroundPackage()) && !isProtected(packageName)
                && !recentUsageDisagrees(packageName)
                && store.inspect(packageName).phase() == RuleEngine.Phase.BLOCKED;
    }

    private void claimGrace(String packageName) {
        try {
            if (stillBlocking(packageName) && store.claimGrace(packageName)) {
                removeOverlay();
            }
            evaluate();
        } catch (RuntimeException exception) {
            stopObservation();
            Log.w(TAG, "Could not start the grace period", exception);
        }
    }

    private void launchChallenge(String packageName) {
        long token = 0;
        try {
            token = stillBlocking(packageName) ? store.beginChallenge(packageName) : 0;
            if (token > 0) {
                stopObservation();
                Intent challenge = new Intent(this, ChallengeActivity.class)
                        .putExtra("token", token).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(challenge);
            } else {
                evaluate();
            }
        } catch (RuntimeException exception) {
            if (token > 0) {
                store.cancelChallenge(token);
            }
            stopObservation();
            Log.w(TAG, "Could not open the challenge", exception);
        }
    }

    private void removeOverlay() {
        if (overlay == null) {
            return;
        }
        ScrollView layout = overlay;
        overlay = null;
        overlayPackage = null;
        overlayStatus = null;
        graceButton = null;
        challengeButton = null;
        layout.setVisibility(View.GONE);
        if (layout.getParent() != null) {
            try {
                windows.removeViewImmediate(layout);
            } catch (RuntimeException exception) {
                // The system can detach an overlay during the same transition that ends observation.
                if (!layout.isAttachedToWindow()) return;
                connected = false;
                if (store != null) store.setMonitorConnected(false);
                disableSelf();
                Log.w(TAG, "Disabled monitoring after overlay cleanup failed", exception);
            }
        }
    }
}
