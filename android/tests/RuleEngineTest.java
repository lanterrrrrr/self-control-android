package com.focusguard.selfcontrol;

import java.io.IOException;
import java.util.Arrays;

public final class RuleEngineTest {
    private static final long DAY = 20_778;
    private static final long NOW = 900_000;
    private static final int BOOT = 4;
    private static final String A = "test.apps.first";
    private static final String B = "test.apps.second";
    private static final String C = "test.apps.third";

    public static void main(String[] args) throws Exception {
        normalUsageAndNewDay();
        separateGraceForEachApp();
        graceDoesNotSurviveDeviceReboot();
        challengeForegroundBalanceAndSharedCount();
        challengeCanFollowGraceAndViceVersa();
        configChangesRetainUsage();
        pauseAndResume();
        persistenceAndCorruption();
        extremeDateRollbackPreservesAllAllowances();
        System.out.println("9 rule-engine scenarios passed");
    }

    private static RuleEngine withApps(Integer minutes) {
        RuleEngine engine = new RuleEngine();
        check(engine.advanceDay(DAY), "initialize date");
        engine.configure(A, minutes);
        engine.configure(B, minutes);
        engine.configure(C, minutes);
        return engine;
    }

    private static void normalUsageAndNewDay() {
        RuleEngine engine = withApps(1);
        engine.addForeground(A, 30_000, NOW, BOOT);
        check(engine.inspect(A, NOW, BOOT).remainingMs() == 30_000, "partial limit");
        check(engine.inspect(B, NOW, BOOT).remainingMs() == 60_000, "independent quota");
        engine.addForeground(A, 35_000, NOW + 35_000, BOOT);
        check(engine.inspect(A, NOW, BOOT).phase() == RuleEngine.Phase.BLOCKED, "quota reached");
        check(engine.inspect(A, NOW, BOOT).usedMs() == 60_000, "charge through limit");
        check(engine.advanceDay(DAY + 1), "new date");
        check(engine.inspect(A, NOW, BOOT).remainingMs() == 60_000, "next-day ordinary balance");
        check(!engine.advanceDay(DAY), "reject date rollback");
        check(engine.day() == DAY + 1, "date preserved during rollback");
    }

    private static void separateGraceForEachApp() {
        RuleEngine engine = withApps(0);
        check(engine.claimGrace(A, NOW, BOOT), "A grace");
        check(!engine.claimGrace(A, NOW, BOOT), "duplicate A grace");
        check(engine.claimGrace(B, NOW + 3_000, BOOT), "independent B grace");
        check(engine.inspect(A, NOW + 60_000, BOOT).remainingMs() == 60_000, "grace wall time");
        check(engine.inspect(A, NOW + 120_000, BOOT).phase() == RuleEngine.Phase.BLOCKED, "grace deadline");
        check(!engine.claimGrace(A, NOW + 122_000, BOOT), "one grace per app");
        check(engine.advanceDay(DAY + 1), "new day");
        check(engine.claimGrace(A, NOW + 122_000, BOOT), "next-day grace");
    }

    private static void graceDoesNotSurviveDeviceReboot() throws IOException {
        RuleEngine engine = withApps(0);
        check(engine.claimGrace(A, NOW, BOOT), "start grace");
        RuleEngine restored = RuleEngine.decode(engine.encode());
        check(restored.inspect(A, NOW + 1_000, BOOT).remainingMs() == 119_000, "restore process grace");
        check(restored.inspect(A, 2_000, BOOT + 1).phase() == RuleEngine.Phase.BLOCKED, "device reboot closes grace");
        check(!restored.claimGrace(A, 2_000, BOOT + 1), "boot cannot replenish grace");
        check(restored.claimGrace(B, 2_000, -1), "unknown boot still offers grace during this process");
        check(restored.inspect(B, 5_000, -1).remainingMs() == 117_000, "unknown boot in-process grace");
        restored = RuleEngine.decode(restored.encode());
        check(restored.inspect(B, 5_000, -1).phase() == RuleEngine.Phase.BLOCKED, "unknown boot cannot restore grace");
        check(!restored.claimGrace(B, 5_000, -1), "unknown boot stays marked claimed");
    }

    private static void challengeForegroundBalanceAndSharedCount() {
        RuleEngine engine = withApps(0);
        check(engine.grantChallenge(A, DAY, NOW, BOOT), "first success");
        check(!engine.grantChallenge(A, DAY, NOW, BOOT), "duplicate grant cannot stack");
        check(engine.inspect(B, NOW, BOOT).phase() == RuleEngine.Phase.BLOCKED, "B cannot borrow A balance");
        engine.addForeground(A, 70_000, NOW + 70_000, BOOT);
        check(engine.inspect(A, NOW + 670_000, BOOT).remainingMs() == 230_000, "away time excluded");
        check(engine.grantChallenge(B, DAY, NOW + 670_000, BOOT), "second success");
        check(!engine.grantChallenge(C, DAY, NOW + 670_000, BOOT), "shared limit");
        engine.addForeground(A, 230_000, NOW + 900_000, BOOT);
        check(engine.inspect(A, NOW + 900_000, BOOT).phase() == RuleEngine.Phase.BLOCKED, "depleted balance");
        check(engine.inspect(A, NOW + 900_000, BOOT).usedMs() == 300_000, "challenge usage contributes to total");
        check(engine.advanceDay(DAY + 1), "advance to tomorrow");
        check(engine.inspect(B, NOW + 900_000, BOOT).phase() == RuleEngine.Phase.BLOCKED, "unused grant expires at midnight");
        check(!engine.grantChallenge(C, DAY, NOW + 900_000, BOOT), "prior-day callback cannot grant");
        check(engine.grantChallenge(C, DAY + 1, NOW + 900_000, BOOT), "tomorrow replenishes two challenges");
    }

    private static void challengeCanFollowGraceAndViceVersa() {
        RuleEngine engine = withApps(0);
        check(engine.claimGrace(A, NOW, BOOT), "start grace");
        check(!engine.grantChallenge(A, DAY, NOW, BOOT), "no challenge stacking over grace");
        engine.addForeground(A, 10_000, NOW + 127_000, BOOT);
        check(engine.inspect(A, NOW + 127_000, BOOT).usedMs() == 3_000, "charge only valid grace time");
        check(engine.grantChallenge(A, DAY, NOW + 127_000, BOOT), "challenge remains after grace");
        check(engine.grantChallenge(B, DAY, NOW + 127_000, BOOT), "B can start with challenge");
        check(!engine.claimGrace(B, NOW + 128_000, BOOT), "no grace stacking over challenge");
        engine.addForeground(B, 300_000, NOW + 427_000, BOOT);
        check(engine.claimGrace(B, NOW + 427_000, BOOT), "B grace remains after challenge");
    }

    private static void configChangesRetainUsage() {
        RuleEngine engine = withApps(5);
        engine.addForeground(A, 90_000, NOW, BOOT);
        engine.configure(A, 9);
        check(engine.inspect(A, NOW, BOOT).limitMinutes() == 5, "increase waits for next day");
        engine.configure(A, 1);
        check(engine.inspect(A, NOW, BOOT).phase() == RuleEngine.Phase.BLOCKED, "decrease applies immediately");
        engine.configure(A, null);
        check(engine.inspect(A, NOW, BOOT).usedMs() == 90_000, "edit preserves today's usage");
        check(engine.rules().stream().filter(rule -> rule.pkg().equals(A)).findFirst().orElseThrow().pending(),
                "pending removal is visible");
        check(engine.advanceDay(DAY + 1), "new day after edit");
        check(engine.inspect(A, NOW, BOOT).phase() == RuleEngine.Phase.UNMANAGED, "remove on next day");
    }

    private static void pauseAndResume() {
        RuleEngine engine = withApps(0);
        engine.setPaused(true);
        engine.setPaused(true);
        check(!engine.claimGrace(A, NOW, BOOT), "paused grace unavailable");
        check(!engine.grantChallenge(A, DAY, NOW, BOOT), "paused challenge unavailable");
        engine.setPaused(false);
        check(engine.grantChallenge(A, DAY, NOW, BOOT), "challenge after resume");
        engine.setPaused(true);
        engine.addForeground(A, 100_000, NOW + 100_000, BOOT);
        engine.setPaused(false);
        check(engine.inspect(A, NOW + 100_000, BOOT).remainingMs() == 300_000, "paused time not charged");
        check(engine.history().stream().filter(summary -> summary.day() == DAY).findFirst().orElseThrow().pauseCount()
                == 2, "only pause transitions counted");
    }

    private static void persistenceAndCorruption() throws IOException {
        RuleEngine engine = withApps(0);
        check(engine.grantChallenge(A, DAY, NOW, BOOT), "persistent balance granted");
        engine.addForeground(A, 70_000, NOW + 70_000, BOOT);
        engine.configure(B, 20);
        engine.setPaused(true);
        RuleEngine restored = RuleEngine.decode(engine.encode());
        check(restored.paused(), "pause survives reload");
        check(restored.inspect(A, 3_000, BOOT + 1).remainingMs() == 230_000, "challenge survives reboot");
        check(restored.history().stream().filter(summary -> summary.day() == DAY).findFirst().orElseThrow().usedMs()
                == 70_000, "persist usage summary");
        check(restored.advanceDay(DAY + 1), "restored day advances");
        check(restored.inspect(B, NOW, BOOT).limitMinutes() == 20, "persist pending edit");
        try {
            RuleEngine.decode(new byte[] {1, 2, 3});
            throw new AssertionError("corrupt saved state must fail to load");
        } catch (IOException expected) {
            // Storage reports corruption and the app pauses enforcement.
        }
    }

    private static void extremeDateRollbackPreservesAllAllowances() throws IOException {
        RuleEngine engine = withApps(0);
        check(engine.claimGrace(A, NOW, BOOT), "A claims the grace before clock shift");
        check(engine.grantChallenge(B, DAY, NOW, BOOT), "B claims one challenge before clock shift");
        engine.addForeground(B, 37_000, NOW + 37_000, BOOT);
        engine.configure(C, 14);
        byte[] before = engine.encode();
        check(!engine.advanceDay(Long.MIN_VALUE), "failed or unset epoch day cannot roll state back");
        check(!engine.advanceDay(DAY - 40_000), "large valid rollback rejected");
        check(!engine.advanceDay(Long.MAX_VALUE), "out-of-range future value rejected");
        check(Arrays.equals(before, engine.encode()), "invalid dates never mutate persisted state");
        check(engine.advanceDay(DAY), "corrected clock returns to same day");
        check(engine.inspect(B, NOW + 37_000, BOOT).remainingMs() == 263_000, "balance survives clock correction");
        check(!engine.claimGrace(A, NOW + 121_000, BOOT), "no repeat grace after clock correction");
        check(engine.inspect(C, NOW + 121_000, BOOT).limitMinutes() == 0, "pending edit remains deferred");
    }

    private static void check(boolean condition, String behavior) {
        if (!condition) {
            throw new AssertionError(behavior);
        }
    }
}
