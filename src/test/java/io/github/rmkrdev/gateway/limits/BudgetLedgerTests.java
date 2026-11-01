package io.github.rmkrdev.gateway.limits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rmkrdev.gateway.api.Usage;
import io.github.rmkrdev.gateway.call.CallOutcome;
import io.github.rmkrdev.gateway.limits.BudgetProperties.Budget;
import io.github.rmkrdev.gateway.tracking.ClientSpend;
import io.github.rmkrdev.gateway.tracking.PriceTable;
import io.github.rmkrdev.gateway.tracking.PricingProperties;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BudgetLedgerTests {

    private static final ZoneId UTC = ZoneOffset.UTC;

    /** Stored spend by period start, as the database would return it. */
    private final Map<Instant, Map<String, ClientSpend>> stored = new HashMap<>();
    private final List<Instant> queries = new ArrayList<>();
    private final AtomicInteger flushes = new AtomicInteger();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-01T10:00:00Z"));
    private boolean databaseDown;

    private PriceTable prices;

    @BeforeEach
    void prices() {
        prices = new PriceTable(new PricingProperties("USD", List.of(new PricingProperties.Price(
                "openai", "gpt-4o-mini", new BigDecimal("1.00"), new BigDecimal("2.00"), null, null, null))));
    }

    private BudgetLedger ledger(Budget defaults, Map<String, Budget> clients) {
        return ledger(defaults, clients, UTC, 0.8);
    }

    private BudgetLedger ledger(Budget defaults, Map<String, Budget> clients, ZoneId zone, double warnAt) {
        BudgetProperties properties = new BudgetProperties(true, zone, Duration.ofSeconds(15), warnAt, defaults,
                clients);
        return new BudgetLedger(properties, start -> {
            if (databaseDown) {
                throw new IllegalStateException("connection refused");
            }
            queries.add(start);
            return stored.getOrDefault(start, Map.of());
        }, flushes::incrementAndGet, prices, clock);
    }

    private static Budget dailyTokens(long tokens) {
        return new Budget(tokens, null, null, null, false);
    }

    private static Budget dailyCost(String cost) {
        return new Budget(null, null, new BigDecimal(cost), null, false);
    }

    private static CallOutcome finished(String client, int prompt, int completion) {
        return new CallOutcome("gw-1", Instant.parse("2026-11-01T10:00:00Z"), client, "chat", "openai",
                "gpt-4o-mini", true, List.of(), new Usage(prompt, completion), "stop", Duration.ZERO, Duration.ZERO,
                null);
    }

    private void storeDay(String client, long tokens, String cost) {
        stored.computeIfAbsent(Instant.parse("2026-11-01T00:00:00Z"), k -> new HashMap<>())
                .put(client, new ClientSpend(tokens, new BigDecimal(cost)));
    }

    @Test
    void underBudgetPassesWithoutWarning() {
        storeDay("billing-api", 100, "0");

        assertThat(ledger(dailyTokens(1_000), Map.of()).admit("billing-api")).isNull();
        assertThat(flushes).hasValue(1);
    }

    @Test
    void refusesWhenStoredSpendReachesTheBudgetUntilTheNextDay() {
        storeDay("billing-api", 1_000, "0");

        LimitExceededException e = catchThrowableOfType(LimitExceededException.class,
                () -> ledger(dailyTokens(1_000), Map.of()).admit("billing-api"));

        assertThat(e.code()).isEqualTo(LimitExceededException.BUDGET_EXCEEDED);
        assertThat(e.limit()).isEqualTo("daily-tokens");
        assertThat(e.resetsAt()).isEqualTo(Instant.parse("2026-11-02T00:00:00Z"));
        assertThat(e.retryAfter()).isEqualTo(Duration.ofHours(14));
        assertThat(e.getMessage()).isEqualTo("Client 'billing-api' has used its daily-tokens budget of 1000 tokens");
    }

    @Test
    void costBudgetCountsCallsFinishedSinceTheLastRefresh() {
        BudgetLedger ledger = ledger(dailyCost("0.01"), Map.of());
        assertThat(ledger.admit("billing-api")).isNull();

        // 4,000 x 1.00 + 1,000 x 2.00 per million = 0.006
        ledger.completed(finished("billing-api", 4_000, 1_000));
        assertThat(ledger.admit("billing-api")).isNull();
        ledger.completed(finished("billing-api", 4_000, 1_000));

        LimitExceededException e = catchThrowableOfType(LimitExceededException.class,
                () -> ledger.admit("billing-api"));
        assertThat(e.limit()).isEqualTo("daily-cost");
        assertThat(e.getMessage()).endsWith("daily-cost budget of 0.01 USD");
    }

    @Test
    void warnsFromTheThresholdWithTheMostUsedBudget() {
        storeDay("billing-api", 850, "0.50");

        String warning = ledger(new Budget(1_000L, null, new BigDecimal("1.00"), null, false), Map.of())
                .admit("billing-api");

        assertThat(warning).isEqualTo("daily-tokens=85%");
    }

    @Test
    void warnAtOneTurnsWarningsOff() {
        storeDay("billing-api", 990, "0");

        assertThat(ledger(dailyTokens(1_000), Map.of(), UTC, 1.0).admit("billing-api")).isNull();
    }

    @Test
    void zeroBlocksEveryCall() {
        assertThat(catchThrowableOfType(LimitExceededException.class,
                () -> ledger(dailyCost("0"), Map.of()).admit("anyone"))).isNotNull();
    }

    @Test
    void clientEntryReplacesTheDefault() {
        storeDay("batch-indexer", 5_000, "0");
        storeDay("search", 5_000, "0");
        storeDay("other", 5_000, "0");
        BudgetLedger ledger = ledger(dailyTokens(1_000), Map.of(
                "Batch-Indexer", new Budget(null, null, null, null, true),
                "search", new Budget(null, null, null, new BigDecimal("100"), false)));

        assertThat(ledger.admit("batch-indexer")).isNull();
        assertThat(ledger.admit("search")).isNull();
        assertThat(catchThrowableOfType(LimitExceededException.class, () -> ledger.admit("other"))).isNotNull();
    }

    @Test
    void readsStoredSpendAgainAfterTheRefreshInterval() {
        BudgetLedger ledger = ledger(dailyTokens(1_000), Map.of());
        ledger.admit("billing-api");
        storeDay("billing-api", 1_000, "0");

        clock.advance(Duration.ofSeconds(10));
        assertThat(ledger.admit("billing-api")).isNull();

        clock.advance(Duration.ofSeconds(5));
        assertThat(catchThrowableOfType(LimitExceededException.class, () -> ledger.admit("billing-api")))
                .isNotNull();
        assertThat(flushes).hasValue(2);
    }

    @Test
    void aNewDayStartsFromZero() {
        storeDay("billing-api", 1_000, "0");
        BudgetLedger ledger = ledger(dailyTokens(1_000), Map.of());
        assertThat(catchThrowableOfType(LimitExceededException.class, () -> ledger.admit("billing-api")))
                .isNotNull();

        clock.set(Instant.parse("2026-11-02T00:00:01Z"));

        assertThat(ledger.admit("billing-api")).isNull();
        assertThat(queries).contains(Instant.parse("2026-11-02T00:00:00Z"));
    }

    @Test
    void daysAndMonthsStartInTheConfiguredZone() {
        ZoneId kolkata = ZoneId.of("Asia/Kolkata");
        // 2026-11-02 01:30 in Kolkata: the day started at 18:30 UTC, the month on 31 October
        Instant monthStart = Instant.parse("2026-10-31T18:30:00Z");
        stored.put(monthStart, Map.of("billing-api", new ClientSpend(10, BigDecimal.ZERO)));
        clock.set(Instant.parse("2026-11-01T20:00:00Z"));
        BudgetLedger ledger = ledger(new Budget(null, 5L, null, null, false), Map.of(), kolkata, 0.8);

        LimitExceededException e = catchThrowableOfType(LimitExceededException.class,
                () -> ledger.admit("billing-api"));

        assertThat(queries).containsExactly(Instant.parse("2026-11-01T18:30:00Z"), monthStart);
        assertThat(e.limit()).isEqualTo("monthly-tokens");
        assertThat(e.resetsAt()).isEqualTo(Instant.parse("2026-11-30T18:30:00Z"));
    }

    @Test
    void storedUsageThatCannotBeReadLetsCallsThrough() {
        databaseDown = true;

        assertThat(ledger(dailyTokens(1_000), Map.of()).admit("billing-api")).isNull();
    }

    @Test
    void keepsTheLastKnownNumbersWhenARefreshFails() {
        storeDay("billing-api", 1_000, "0");
        BudgetLedger ledger = ledger(dailyTokens(1_000), Map.of());
        assertThat(catchThrowableOfType(LimitExceededException.class, () -> ledger.admit("billing-api")))
                .isNotNull();

        databaseDown = true;
        clock.advance(Duration.ofSeconds(20));

        assertThat(catchThrowableOfType(LimitExceededException.class, () -> ledger.admit("billing-api")))
                .isNotNull();
    }

    @Test
    void disabledOrUnsetChecksNothing() {
        BudgetProperties off = new BudgetProperties(false, UTC, Duration.ofSeconds(15), 0.8, dailyTokens(0), Map.of());
        BudgetProperties unset = new BudgetProperties(true, UTC, Duration.ofSeconds(15), 0.8,
                new Budget(null, null, null, null, false), Map.of("a", new Budget(null, null, null, null, true)));

        assertThat(off.active()).isFalse();
        assertThat(unset.active()).isFalse();
        assertThat(new BudgetLedger(off, start -> Map.of(), () -> { }, prices, clock).admit("a")).isNull();
        assertThat(queries).isEmpty();
    }

    static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        void set(Instant instant) {
            now = instant;
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
