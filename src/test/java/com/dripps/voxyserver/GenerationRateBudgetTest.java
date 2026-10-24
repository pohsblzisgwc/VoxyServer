package com.dripps.voxyserver;

import com.dripps.voxyserver.server.background.GenerationRateBudget;

public final class GenerationRateBudgetTest {
    public static void runAllTests() {
        var budget = new GenerationRateBudget();
        int total = 0;
        for (int tick = 0; tick < 1200; tick++) {
            long now = tick * 50_000_000L;
            require(budget.allowance(600000, now) == 500, "high rate allowance unavailable");
            int batch = 0;
            while (budget.tryAcquire(600000, now)) {
                batch++;
                total++;
            }
            require(batch == 500, "batch capacity exceeded or high rate throttled");
        }
        require(total == 600000, "minute total incorrect");
        require(!budget.tryAcquire(600000, 59_999_999_999L), "minute ceiling exceeded");
        require(budget.tryAcquire(600000, 60_000_000_000L), "next minute failed recovery");
        var low = new GenerationRateBudget();
        require(low.tryAcquire(6, 0), "first default admission missing");
        require(!low.tryAcquire(6, 9_999_999_999L), "default interval shortened");
        require(low.tryAcquire(6, 10_000_000_000L), "default admission never resumed");
        require(low.allowance(6, 600_000_000_000L) == 1, "idle accumulated a catch-up burst");
        var changed = new GenerationRateBudget();
        require(changed.tryAcquire(600000, 0), "initial changed admission");
        require(
                changed.allowance(1, 0) == 0 && !changed.tryAcquire(1, 0),
                "rate change reset issued quota");
        require(changed.tryAcquire(1, 60_000_000_000L), "lowered rate failed recovery");
        require(changed.allowance(1, 1) == 0, "clock rollback accumulated credit");
        for (int invalid : new int[] {-1, 0, 600001, Integer.MAX_VALUE}) {
            try {
                new GenerationRateBudget().tryAcquire(invalid, 0);
                throw new AssertionError("invalid rate accepted");
            } catch (IllegalArgumentException expected) {
            }
        }
        System.out.println(
                "PASS GenerationRateBudgetTest: 600000 admissions/minute in 500/tick batches,"
                    + " unchanged 6/min default, idle and rate-change bounds");
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
