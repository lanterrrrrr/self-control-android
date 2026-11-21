package com.focusguard.selfcontrol;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

public final class MainActivity extends Activity {
    private static final int BG = 0xfff4f7f3;
    private static final int INK = 0xff18332a;
    private static final int MUTED = 0xff4c6658;
    private static final int SOFT = 0xffdfece0;
    private static final int GREEN = 0xff20604a;
    private static final int DEEP = 0xff143c31;
    private static final int PALE = 0xffdaedce;
    private static final int WHITE = 0xffffffff;
    private static final int ALERT = 0xff79382b;

    private enum Page { HOME, SETTINGS, ADVANCED, EMERGENCY }

    private AppStore store;
    private Page currentPage = Page.HOME;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private TextView supervisionBadge;
    private LinearLayout supervisionPanel;
    private SupervisionStatus displayedStatus;
    private final Runnable refreshWhileVisible = new Runnable() {
        @Override public void run() {
            if (currentPage == Page.HOME) updateSupervision(supervisionStatus());
            uiHandler.postDelayed(this, 1_500);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = AppStore.get(this);
        if (state != null) currentPage = Page.valueOf(state.getString("settingsPage", Page.HOME.name()));
    }

    @Override protected void onResume() {
        super.onResume();
        render();
        uiHandler.postDelayed(refreshWhileVisible, 1_500);
    }

    @Override protected void onPause() {
        uiHandler.removeCallbacks(refreshWhileVisible);
        super.onPause();
    }

    private void render() {
        if (currentPage != Page.HOME) {
            renderSettings();
            return;
        }
        SupervisionStatus supervision = supervisionStatus();
        boolean valid = supervision.clockValid();
        List<RuleEngine.DaySummary> history = store.history();
        List<RuleEngine.Rule> rules = store.rules();

        LinearLayout page = setupPage();

        TextView title = text("自控力", 32, INK, true);
        title.setAccessibilityHeading(true);
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        titleRow.addView(button("设置", SOFT, INK, view -> showPage(Page.SETTINGS)));
        page.addView(titleRow);
        page.addView(text("应用限额与使用记录", 14, MUTED, false));

        LinearLayout hero = column(19);
        hero.setBackground(background(DEEP, 24));
        append(page, hero, 20);
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView overview = text("今日受管应用用时", 14, PALE, true);
        top.addView(overview, new LinearLayout.LayoutParams(0, -2, 1));
        supervisionBadge = text("", 12, DEEP, true);
        supervisionBadge.setBackground(background(PALE, 30));
        supervisionBadge.setPadding(dp(10), dp(5), dp(10), dp(5));
        top.addView(supervisionBadge);
        hero.addView(top);
        long today = store.day();
        long used = 0;
        for (RuleEngine.DaySummary summary : history) {
            if (summary.day() == today) used = summary.usedMs();
        }
        append(hero, text(duration(used), 33, WHITE, true), 9);
        append(hero, text("挑战剩余 " + store.remainingChallenges() + " 次 · 每日时区 " + store.timeZone(), 13, PALE, false), 4);

        supervisionPanel = card();
        append(page, supervisionPanel, 16);
        displayedStatus = null;
        updateSupervision(supervision);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        append(page, header, 20);
        TextView section = text("受管应用", 21, INK, true);
        section.setAccessibilityHeading(true);
        header.addView(section, new LinearLayout.LayoutParams(0, -2, 1));
        Button add = button("添加应用", GREEN, WHITE, view -> showPicker());
        add.setEnabled(valid);
        header.addView(add);

        if (rules.isEmpty()) {
            LinearLayout empty = card();
            append(page, empty, 8);
            empty.addView(text("添加想限制的应用，并为它设置每日使用额度。", 14, MUTED, false));
        }
        for (RuleEngine.Rule rule : rules) addRule(page, rule, valid);

        TextView report = text("过去 7 天", 21, INK, true);
        report.setAccessibilityHeading(true);
        append(page, report, 22);
        LinearLayout reportCard = card();
        append(page, reportCard, 9);
        if (history.isEmpty()) reportCard.addView(text("开始监管后，这里会显示每日用时和放行次数。", 14, MUTED, false));
        DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("M 月 d 日", Locale.CHINA);
        for (RuleEngine.DaySummary summary : history) {
            String date = LocalDate.ofEpochDay(summary.day()).format(dateFormat);
            append(reportCard, text(date + " · " + duration(summary.usedMs()) + "\n收尾 " + summary.graceCount()
                    + " 次 · 挑战 " + summary.challengeCount() + " 次 · 暂停 " + summary.pauseCount() + " 次",
                    13, MUTED, false), 5);
        }

    }

    private SupervisionStatus supervisionStatus() {
        boolean validTime = store.clockValid();
        return new SupervisionStatus(store.paused(), store.monitorEnabled(), store.monitorConnected(),
                AppStore.usageAllowed(this), store.ready(), validTime);
    }

    private void updateSupervision(SupervisionStatus status) {
        if (status.equals(displayedStatus)) return;
        displayedStatus = status;
        supervisionPanel.removeAllViews();
        supervisionPanel.setVisibility(status.state() == SupervisionStatus.State.ACTIVE ? View.GONE : View.VISIBLE);
        switch (status.state()) {
            case ACTIVE -> supervisionBadge.setText("监管运行中");
            case PAUSED -> {
                supervisionBadge.setText("监管已暂停");
                String message = "恢复后继续使用当天的记录和额度。";
                if (!status.accessibilityEnabled()) message += " 系统无障碍开关也处于关闭，恢复后需开启。";
                else if (!status.usageAllowed()) message += " 恢复后需允许使用情况访问。";
                supervisionAction(message, "恢复监管", () -> changePause(true));
            }
            case ACCESSIBILITY_DISABLED -> {
                supervisionBadge.setText("无障碍未开启");
                supervisionAction("在 Android 无障碍设置中开启「自控力」。授权保持开启时，系统会连接监管服务。",
                        "开启无障碍服务", () -> openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
            case USAGE_PERMISSION_REQUIRED -> {
                supervisionBadge.setText("缺少使用情况访问");
                supervisionAction("开启使用情况访问后，应用可以核对前台使用记录。",
                        "打开使用情况访问", () -> openSettings(Settings.ACTION_USAGE_ACCESS_SETTINGS));
            }
            case CONNECTING -> {
                supervisionBadge.setText("等待服务连接");
                supervisionAction("无障碍已开启，正在等待 Android 连接服务。", null, null);
            }
            case CLOCK_ERROR -> {
                supervisionBadge.setText("请核对系统时间");
                supervisionAction("请核对系统日期和时间。",
                        "打开日期和时间设置", () -> openSettings(Settings.ACTION_DATE_SETTINGS));
            }
            case STORAGE_ERROR -> {
                supervisionBadge.setText("监管暂不可用");
                supervisionAction("本地监管记录暂时无法读取。", null, null);
            }
        }
    }

    private void supervisionAction(String message, String label, Runnable action) {
        supervisionPanel.addView(text(message, 14, MUTED, false));
        if (label != null) append(supervisionPanel, button(label, GREEN, WHITE, view -> action.run()), 8);
    }

    private LinearLayout setupPage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout page = column(0);
        page.setPadding(dp(20), dp(18), dp(20), dp(28));
        scroll.addView(page);
        setContentView(scroll);
        return page;
    }

    private void showPage(Page page) {
        currentPage = page;
        render();
    }

    private void renderSettings() {
        LinearLayout page = setupPage();
        page.addView(button("返回", SOFT, INK, view -> onBackPressed()), new LinearLayout.LayoutParams(-2, -2));
        String heading = switch (currentPage) {
            case SETTINGS -> "设置";
            case ADVANCED -> "高级设置";
            case EMERGENCY -> "紧急解除";
            default -> throw new IllegalStateException("Settings page required");
        };
        TextView title = text(heading, 28, INK, true);
        title.setAccessibilityHeading(true);
        append(page, title, 14);

        if (currentPage == Page.SETTINGS) {
            append(page, button("高级设置", SOFT, INK, view -> showPage(Page.ADVANCED)), 16);
        } else if (currentPage == Page.ADVANCED) {
            append(page, button("紧急解除", SOFT, INK, view -> showPage(Page.EMERGENCY)), 16);
        } else {
            boolean paused = store.paused();
            append(page, text(paused ? "监管已暂停。恢复后继续使用当天的记录和额度。"
                    : "应用异常影响正常使用时，可暂停全部应用的拦截。", 15, MUTED, false), 10);
            Button change = button(paused ? "恢复监管" : "紧急暂停", paused ? GREEN : ALERT, WHITE,
                    view -> changePause(paused));
            change.setEnabled(store.ready());
            append(page, change, 16);
            append(page, text("如果自控力无法打开，请从 Android 设置 → 无障碍中关闭「自控力」。",
                    14, MUTED, false), 18);
            append(page, button("打开系统无障碍设置", SOFT, INK,
                    view -> openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS)), 8);
        }
    }

    private void changePause(boolean paused) {
        if (paused) {
            store.setPaused(false);
            render();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("暂停监管？")
                .setMessage("暂停后，受管应用可以正常使用。请在异常处理完毕后回到这里恢复监管。")
                .setNegativeButton("取消", null)
                .setPositiveButton("确认暂停", (dialog, which) -> {
                    store.setPaused(true);
                    render();
                })
                .show();
    }

    @Override public void onBackPressed() {
        switch (currentPage) {
            case HOME -> super.onBackPressed();
            case SETTINGS -> showPage(Page.HOME);
            case ADVANCED -> showPage(Page.SETTINGS);
            case EMERGENCY -> showPage(Page.ADVANCED);
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("settingsPage", currentPage.name());
    }

    private void addRule(LinearLayout page, RuleEngine.Rule rule, boolean valid) {
        RuleEngine.Decision decision = store.inspect(rule.pkg());
        LinearLayout card = card();
        append(page, card, 8);
        card.addView(text(ProtectedApps.label(this, rule.pkg()), 17, INK, true));
        if (rule.minutes() != null) {
            append(card, text("已用 " + duration(decision.usedMs()) + " · 每日额度 " + rule.minutes() + " 分钟", 14, MUTED, false), 4);
            String balance = switch (decision.phase()) {
                case NORMAL -> "正常额度剩余 " + duration(decision.remainingMs());
                case GRACE -> "收尾时间剩余 " + duration(decision.remainingMs());
                case CHALLENGE -> "放行余额 " + duration(decision.remainingMs());
                case BLOCKED -> decision.graceAvailable() ? "已到限 · 可领取最后 2 分钟" : "已到限 · 已领取收尾机会";
                case UNMANAGED -> "待生效";
            };
            append(card, text(balance, 13, GREEN, true), 4);
        }
        if (rule.pending()) {
            String update = rule.pendingMinutes() == null ? "次日移出监管" : "次日起每日额度 " + rule.pendingMinutes() + " 分钟";
            append(card, text(update, 13, MUTED, false), 4);
        }
        Button edit = button("编辑额度", SOFT, INK,
                view -> showLimit(rule.pkg(), rule.pendingMinutes() != null ? rule.pendingMinutes() : rule.minutes(), true));
        edit.setEnabled(valid);
        append(card, edit, 7);
    }

    private void showPicker() {
        Intent launchable = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        Map<String, String> apps = new TreeMap<>();
        for (ResolveInfo app : getPackageManager().queryIntentActivities(launchable, 0)) {
            if (app.activityInfo == null) continue;
            String pkg = app.activityInfo.packageName;
            if (ProtectedApps.isProtected(this, pkg)) continue;
            apps.put(pkg, app.loadLabel(getPackageManager()).toString());
        }
        List<AppChoice> choices = apps.entrySet().stream().map(item -> new AppChoice(item.getKey(), item.getValue()))
                .sorted(java.util.Comparator.comparing(AppChoice::label).thenComparing(AppChoice::pkg)).collect(Collectors.toList());
        LinearLayout content = column(18);
        EditText query = new EditText(this);
        query.setSingleLine();
        query.setHint("搜索应用名称");
        content.addView(query);
        ListView list = new ListView(this);
        content.addView(list, new LinearLayout.LayoutParams(-1, dp(320)));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("添加受管应用").setView(content)
                .setNegativeButton("取消", null).create();
        Runnable refresh = () -> {
            String filter = query.getText().toString().trim().toLowerCase(Locale.ROOT);
            List<AppChoice> visible = choices.stream().filter(app -> app.label().toLowerCase(Locale.ROOT).contains(filter)
                    || app.pkg().toLowerCase(Locale.ROOT).contains(filter)).collect(Collectors.toList());
            list.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, visible));
            list.setOnItemClickListener((parent, view, position, id) -> {
                AppChoice choice = visible.get(position);
                dialog.dismiss();
                RuleEngine.Rule rule = store.rules().stream().filter(item -> item.pkg().equals(choice.pkg())).findFirst().orElse(null);
                showLimit(choice.pkg(), rule == null ? 30 : rule.minutes(), rule != null);
            });
        };
        query.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { refresh.run(); }
            @Override public void afterTextChanged(Editable s) { }
        });
        refresh.run();
        dialog.show();
        if (dialog.getWindow() != null) dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
    }

    private void showLimit(String pkg, Integer minutes, boolean existing) {
        LinearLayout content = column(18);
        EditText input = new EditText(this);
        input.setSingleLine();
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint("每日额度（分钟）");
        input.setText(minutes == null ? "30" : String.valueOf(minutes));
        input.setSelectAllOnFocus(true);
        content.addView(input);
        append(content, text("可设 0 至 1440 分钟。增加额度或移出监管于次日生效。", 13, MUTED, false), 3);
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle(ProtectedApps.label(this, pkg)).setView(content)
                .setPositiveButton("保存额度", null).setNegativeButton("取消", null);
        if (existing) builder.setNeutralButton("移出监管", (dialog, which) -> {
            if (!store.configure(pkg, null)) notifySaveFailed();
            render();
        });
        AlertDialog dialog = builder.create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                int newMinutes = Integer.parseInt(input.getText().toString().trim());
                if (newMinutes < 0 || newMinutes > 1440) throw new NumberFormatException("outside daily range");
                if (!store.configure(pkg, newMinutes)) { notifySaveFailed(); return; }
                dialog.dismiss();
                render();
            } catch (NumberFormatException ex) {
                input.setError("请输入 0 至 1440 的整数");
            }
        });
    }

    private void openSettings(String action) {
        try { startActivity(new Intent(action)); }
        catch (android.content.ActivityNotFoundException ex) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
    }

    private void notifySaveFailed() { Toast.makeText(this, "暂时无法保存设置。", Toast.LENGTH_SHORT).show(); }

    private record AppChoice(String pkg, String label) {
        @Override public String toString() { return label; }
    }

    private static String duration(long millis) {
        long seconds = millis / 1_000;
        if (seconds >= 3600) return (seconds / 3600) + " 小时 " + (seconds % 3600 / 60) + " 分钟";
        if (seconds >= 60) return (seconds / 60) + " 分 " + (seconds % 60) + " 秒";
        return seconds + " 秒";
    }

    private int dp(int units) { return Math.round(units * getResources().getDisplayMetrics().density); }

    private LinearLayout column(int padding) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int px = dp(padding);
        layout.setPadding(px, px, px, px);
        return layout;
    }

    private LinearLayout card() {
        LinearLayout layout = column(15);
        layout.setBackground(background(WHITE, 16));
        return layout;
    }

    private GradientDrawable background(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        if (bold) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return text;
    }

    private Button button(String label, int backgroundColor, int textColor, View.OnClickListener onClick) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextColor(textColor);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setPadding(dp(14), 0, dp(14), 0);
        button.setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x2120604a),
                background(backgroundColor, 12), background(Color.WHITE, 12)));
        button.setOnClickListener(onClick);
        return button;
    }

    private void append(LinearLayout target, View child, int spacing) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(spacing);
        target.addView(child, params);
    }
}
