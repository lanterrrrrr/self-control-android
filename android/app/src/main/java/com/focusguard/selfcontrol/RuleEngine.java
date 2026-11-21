package com.focusguard.selfcontrol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

/** Daily limits and temporary allowances. The caller supplies the clock and serializes access. */
public final class RuleEngine {
    public static final int MAX_MINUTES = 1_440;
    public static final int DAILY_CHALLENGES = 2;
    private static final long MINUTE_MS = 60_000;
    private static final long GRACE_MS = 120_000;
    private static final long CHALLENGE_MS = 300_000;
    private static final int HISTORY_DAYS = 7;
    private static final long NO_DAY = Long.MIN_VALUE;
    private static final long FIRST_SUPPORTED_DAY = LocalDate.MIN.toEpochDay();
    private static final long LAST_SUPPORTED_DAY = LocalDate.MAX.toEpochDay();
    private static final int FORMAT_VERSION = 2;
    private static final int MAX_PACKAGE_COUNT = 10_000;

    public enum Phase { UNMANAGED, NORMAL, GRACE, CHALLENGE, BLOCKED }

    public record Rule(String pkg, Integer minutes, Integer pendingMinutes, boolean pending) {}

    public record Decision(Phase phase, int limitMinutes, long usedMs, long remainingMs,
                           boolean graceAvailable, int challengesLeft) {}

    public record DaySummary(long day, long usedMs, int challengeCount, int pauseCount, int graceCount) {}

    private static final class AppUsage {
        long normalMs;
        long totalMs;
        long graceStartedAt = -1;
        int graceBoot = -1;
        boolean graceClaimed;
        long challengeMs;

        long graceEnd(int bootCount) {
            return graceStartedAt >= 0 && graceBoot == bootCount ? graceStartedAt + GRACE_MS : -1;
        }
    }

    private final Map<String, Rule> rules = new LinkedHashMap<>();
    private final Map<String, AppUsage> usage = new LinkedHashMap<>();
    private final List<DaySummary> previousDays = new ArrayList<>();
    private long day = NO_DAY;
    private int challengeCount;
    private int pauseCount;
    private boolean paused;

    public long day() {
        return day;
    }

    /** Returns false when the supplied date would move the active day backwards. */
    public boolean advanceDay(long epochDay) {
        if (!validDay(epochDay) || epochDay < day) {
            return false;
        }
        if (day == epochDay) {
            return true;
        }
        if (day != NO_DAY) {
            previousDays.add(0, currentSummary());
            previousDays.removeIf(summary -> summary.day() <= epochDay - HISTORY_DAYS);
        }
        applyPendingRules();
        usage.clear();
        challengeCount = 0;
        pauseCount = 0;
        day = epochDay;
        return true;
    }

    private void applyPendingRules() {
        rules.values().removeIf(rule -> rule.pending() && rule.pendingMinutes() == null);
        rules.replaceAll((pkg, rule) -> rule.pending()
                ? new Rule(pkg, rule.pendingMinutes(), null, false)
                : rule);
    }

    public void configure(String pkg, Integer minutes) {
        if (pkg == null || pkg.trim().isEmpty()) {
            throw new IllegalArgumentException("Package is required");
        }
        if (minutes != null && (minutes < 0 || minutes > MAX_MINUTES)) {
            throw new IllegalArgumentException("Daily limit is out of range");
        }
        Rule current = rules.get(pkg);
        if (current == null && minutes == null) {
            return;
        }
        if (current != null && (minutes == null || minutes > current.minutes())) {
            rules.put(pkg, new Rule(pkg, current.minutes(), minutes, true));
        } else {
            rules.put(pkg, new Rule(pkg, minutes, null, false));
        }
    }

    public List<Rule> rules() {
        return new ArrayList<>(rules.values());
    }

    public Decision inspect(String pkg, long elapsedMs, int bootCount) {
        Rule rule = rules.get(pkg);
        int challengesLeft = DAILY_CHALLENGES - challengeCount;
        if (rule == null) {
            return new Decision(Phase.UNMANAGED, 0, 0, 0, false, challengesLeft);
        }
        AppUsage app = usage.get(pkg);
        long total = app == null ? 0 : app.totalMs;
        long normalRemaining = normalRemaining(rule, app);
        if (normalRemaining > 0) {
            return decision(Phase.NORMAL, rule, total, normalRemaining, false);
        }
        if (app != null) {
            long graceEnd = app.graceEnd(bootCount);
            if (elapsedMs >= app.graceStartedAt && elapsedMs < graceEnd) {
                return decision(Phase.GRACE, rule, total, graceEnd - elapsedMs, false);
            }
            if (app.challengeMs > 0) {
                return decision(Phase.CHALLENGE, rule, total, app.challengeMs, false);
            }
        }
        boolean graceAvailable = !paused && (app == null || !app.graceClaimed);
        return decision(Phase.BLOCKED, rule, total, 0, graceAvailable);
    }

    private Decision decision(Phase phase, Rule rule, long usedMs, long remainingMs, boolean graceAvailable) {
        return new Decision(phase, rule.minutes(), usedMs, remainingMs, graceAvailable,
                DAILY_CHALLENGES - challengeCount);
    }

    /** Records [elapsedMs - durationMs, elapsedMs) while the package was usable in the foreground. */
    public boolean addForeground(String pkg, long durationMs, long elapsedMs, int bootCount) {
        Rule rule = rules.get(pkg);
        if (paused || day == NO_DAY || rule == null || durationMs <= 0 || elapsedMs < 0) {
            return false;
        }
        durationMs = Math.min(durationMs, elapsedMs);
        AppUsage app = usage.computeIfAbsent(pkg, unused -> new AppUsage());
        long chargedMs;
        long normalRemaining = normalRemaining(rule, app);
        if (normalRemaining > 0) {
            chargedMs = Math.min(normalRemaining, durationMs);
            app.normalMs += chargedMs;
        } else if (app.challengeMs > 0) {
            chargedMs = Math.min(app.challengeMs, durationMs);
            app.challengeMs -= chargedMs;
        } else {
            long end = Math.min(elapsedMs, app.graceEnd(bootCount));
            long start = Math.max(elapsedMs - durationMs, app.graceStartedAt);
            chargedMs = Math.max(0, end - start);
        }
        app.totalMs += chargedMs;
        return chargedMs > 0;
    }

    public boolean claimGrace(String pkg, long elapsedMs, int bootCount) {
        if (day == NO_DAY || elapsedMs < 0 || elapsedMs > Long.MAX_VALUE - GRACE_MS
                || !inspect(pkg, elapsedMs, bootCount).graceAvailable()) {
            return false;
        }
        AppUsage app = usage.computeIfAbsent(pkg, unused -> new AppUsage());
        app.graceClaimed = true;
        app.graceStartedAt = elapsedMs;
        app.graceBoot = bootCount;
        return true;
    }

    public boolean grantChallenge(String pkg, long expectedDay, long elapsedMs, int bootCount) {
        if (paused || day == NO_DAY || day != expectedDay || elapsedMs < 0 || challengeCount >= DAILY_CHALLENGES
                || inspect(pkg, elapsedMs, bootCount).phase() != Phase.BLOCKED) {
            return false;
        }
        AppUsage app = usage.computeIfAbsent(pkg, unused -> new AppUsage());
        app.challengeMs = CHALLENGE_MS;
        challengeCount++;
        return true;
    }

    private static long normalRemaining(Rule rule, AppUsage app) {
        long normalUsed = app == null ? 0 : app.normalMs;
        return Math.max(0, rule.minutes() * MINUTE_MS - normalUsed);
    }

    public boolean paused() {
        return paused;
    }

    public void setPaused(boolean value) {
        if (value && !paused && day != NO_DAY) {
            pauseCount++;
        }
        paused = value;
    }

    public List<DaySummary> history() {
        List<DaySummary> history = new ArrayList<>();
        if (day != NO_DAY) {
            history.add(currentSummary());
            history.addAll(previousDays);
        }
        return history;
    }

    private DaySummary currentSummary() {
        long total = 0;
        int graceCount = 0;
        for (AppUsage app : usage.values()) {
            total += app.totalMs;
            if (app.graceClaimed) graceCount++;
        }
        return new DaySummary(day, total, challengeCount, pauseCount, graceCount);
    }

    public byte[] encode() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeInt(FORMAT_VERSION);
        output.writeLong(day);
        output.writeBoolean(paused);
        output.writeInt(challengeCount);
        output.writeInt(pauseCount);
        output.writeInt(rules.size());
        for (Rule rule : rules.values()) {
            output.writeUTF(rule.pkg());
            output.writeInt(rule.minutes());
            output.writeBoolean(rule.pending());
            output.writeInt(rule.pendingMinutes() == null ? -1 : rule.pendingMinutes());
        }
        output.writeInt(usage.size());
        for (Map.Entry<String, AppUsage> entry : usage.entrySet()) {
            AppUsage app = entry.getValue();
            output.writeUTF(entry.getKey());
            output.writeLong(app.normalMs);
            output.writeLong(app.totalMs);
            output.writeBoolean(app.graceClaimed);
            output.writeLong(app.graceStartedAt);
            output.writeInt(app.graceBoot);
            output.writeLong(app.challengeMs);
        }
        output.writeInt(previousDays.size());
        for (DaySummary summary : previousDays) {
            output.writeLong(summary.day());
            output.writeLong(summary.usedMs());
            output.writeInt(summary.challengeCount());
            output.writeInt(summary.pauseCount());
            output.writeInt(summary.graceCount());
        }
        byte[] payload = bytes.toByteArray();
        output.writeInt(checksum(payload, payload.length));
        return bytes.toByteArray();
    }

    public static RuleEngine decode(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length < 8) {
            throw new IOException("Rule state is incomplete");
        }
        int payloadLength = bytes.length - Integer.BYTES;
        DataInputStream footer = new DataInputStream(new ByteArrayInputStream(bytes, payloadLength, Integer.BYTES));
        if (footer.readInt() != checksum(bytes, payloadLength)) {
            throw new IOException("Rule state checksum does not match");
        }
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes, 0, payloadLength));
        int version = input.readInt();
        if (version < 1 || version > FORMAT_VERSION) {
            throw new IOException("Rule state format is unsupported");
        }
        RuleEngine engine = new RuleEngine();
        engine.day = input.readLong();
        if (engine.day != NO_DAY && !validDay(engine.day)) {
            throw new IOException("Rule state date is invalid");
        }
        engine.paused = input.readBoolean();
        engine.challengeCount = readCount(input, DAILY_CHALLENGES);
        engine.pauseCount = readCount(input, Integer.MAX_VALUE);
        engine.readRules(input);
        engine.readUsage(input);
        engine.readHistory(input, version);
        if (input.available() != 0) {
            throw new IOException("Rule state has unexpected data");
        }
        return engine;
    }

    private void readRules(DataInputStream input) throws IOException {
        int count = readCount(input, MAX_PACKAGE_COUNT);
        for (int index = 0; index < count; index++) {
            String pkg = input.readUTF();
            int minutes = readCount(input, MAX_MINUTES);
            boolean pending = input.readBoolean();
            int next = input.readInt();
            if (pkg.trim().isEmpty() || rules.containsKey(pkg) || next < -1 || next > MAX_MINUTES
                    || (next >= 0 && (!pending || next <= minutes))) {
                throw new IOException("Saved application rule is invalid");
            }
            rules.put(pkg, new Rule(pkg, minutes, next < 0 ? null : next, pending));
        }
    }

    private void readUsage(DataInputStream input) throws IOException {
        int count = readCount(input, rules.size());
        for (int index = 0; index < count; index++) {
            String pkg = input.readUTF();
            AppUsage app = new AppUsage();
            app.normalMs = input.readLong();
            app.totalMs = input.readLong();
            app.graceClaimed = input.readBoolean();
            app.graceStartedAt = input.readLong();
            app.graceBoot = input.readInt();
            app.challengeMs = input.readLong();
            if (!rules.containsKey(pkg) || usage.containsKey(pkg) || app.normalMs < 0
                    || app.normalMs > MAX_MINUTES * MINUTE_MS || app.totalMs < app.normalMs
                    || app.totalMs > app.normalMs + GRACE_MS + DAILY_CHALLENGES * CHALLENGE_MS
                    || app.challengeMs < 0 || app.challengeMs > CHALLENGE_MS || app.graceStartedAt < -1
                    || app.graceStartedAt > Long.MAX_VALUE - GRACE_MS
                    || (app.graceStartedAt >= 0 && !app.graceClaimed)) {
                throw new IOException("Saved usage is invalid");
            }
            if (app.graceBoot < 0) {
                app.graceStartedAt = -1;
            }
            usage.put(pkg, app);
        }
    }

    private void readHistory(DataInputStream input, int version) throws IOException {
        int count = readCount(input, HISTORY_DAYS - 1);
        long newerDay = day;
        for (int index = 0; index < count; index++) {
            long savedDay = input.readLong();
            long usedMs = input.readLong();
            int challenges = readCount(input, DAILY_CHALLENGES);
            int pauses = readCount(input, Integer.MAX_VALUE);
            int grace = version >= 2 ? readCount(input, MAX_PACKAGE_COUNT) : 0;
            if (!validDay(savedDay) || savedDay <= day - HISTORY_DAYS || savedDay >= newerDay || usedMs < 0) {
                throw new IOException("Saved history is invalid");
            }
            previousDays.add(new DaySummary(savedDay, usedMs, challenges, pauses, grace));
            newerDay = savedDay;
        }
    }

    private static int readCount(DataInputStream input, int max) throws IOException {
        int value = input.readInt();
        if (value < 0 || value > max) {
            throw new IOException("Rule state count is out of range");
        }
        return value;
    }

    private static boolean validDay(long day) {
        return day >= FIRST_SUPPORTED_DAY && day <= LAST_SUPPORTED_DAY;
    }

    private static int checksum(byte[] bytes, int length) {
        CRC32 crc = new CRC32();
        crc.update(bytes, 0, length);
        return (int) crc.getValue();
    }
}
