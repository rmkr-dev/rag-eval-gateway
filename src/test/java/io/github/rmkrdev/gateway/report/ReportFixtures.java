package io.github.rmkrdev.gateway.report;

import io.github.rmkrdev.gateway.tracking.CallRecord;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** A small, known set of calls on 2026-10-26 (UTC) shared by the report tests. */
public final class ReportFixtures {

    public static final Instant DAY = Instant.parse("2026-10-26T00:00:00Z");

    private ReportFixtures() {
    }

    public static CallRecord call(String id, Instant at, String client, String model, String provider,
            String status, int prompt, int completion, Integer cacheRead, String cost, Long ttft, long total) {
        return new CallRecord(id, at, client, model, provider, provider == null ? null : model + "-upstream",
                ttft != null, status, CallRecord.OK.equals(status) ? null : "all_targets_failed",
                CallRecord.OK.equals(status) ? "stop" : null, prompt, completion, cacheRead, null,
                cost == null ? null : new BigDecimal(cost), cost == null ? null : "USD",
                cost == null ? null : "initial", ttft, total, 0);
    }

    /**
     * billing-api: 10 ok calls on chat/openai at 09:xx, total 100..1000 ms, ttft 10..100 ms, 0.01 each.
     * search: 4 ok calls on embed/local at 10:xx, total 50 ms, no price; 1 failed call, no provider.
     * One call the day before, outside the range.
     */
    public static List<CallRecord> calls() {
        List<CallRecord> calls = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            calls.add(call("gw-b" + i, DAY.plusSeconds(9 * 3600 + i * 60), "billing-api", "chat", "openai",
                    CallRecord.OK, 1000, 200, 100, "0.01", i * 10L, i * 100L));
        }
        for (int i = 1; i <= 4; i++) {
            calls.add(call("gw-s" + i, DAY.plusSeconds(10 * 3600 + i * 60), "search", "embed", "local",
                    CallRecord.OK, 500, 0, null, null, null, 50));
        }
        calls.add(call("gw-s-err", DAY.plusSeconds(10 * 3600 + 30 * 60), "search", "embed", null,
                CallRecord.ERROR, 0, 0, null, null, null, 3000));
        calls.add(call("gw-old", DAY.minusSeconds(60), "billing-api", "chat", "openai",
                CallRecord.OK, 1000, 200, null, "5", 1L, 1L));
        return calls;
    }
}
