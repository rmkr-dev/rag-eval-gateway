package io.github.rmkrdev.gateway.limits;

import io.github.rmkrdev.gateway.api.Usage;
import io.github.rmkrdev.gateway.call.CallObserver;
import io.github.rmkrdev.gateway.call.CallOutcome;
import io.github.rmkrdev.gateway.limits.BudgetProperties.Budget;
import io.github.rmkrdev.gateway.tracking.ClientSpend;
import io.github.rmkrdev.gateway.tracking.PriceTable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What each client has spent this day and month, and whether it may make another call.
 * <p>
 * Spend comes from the stored calls, read again every {@code refresh-interval} (after the
 * pending calls are written), plus the calls this replica finished since. The call that
 * crosses a budget is allowed to finish; the next one is refused. With several replicas a
 * client can overshoot by what the others serve in one refresh interval.
 * <p>
 * If stored usage cannot be read, calls go through on the last known numbers and the failure is
 * logged: a database problem never stops the gateway answering.
 */
public class BudgetLedger implements CallObserver {

    private static final Logger log = LoggerFactory.getLogger(BudgetLedger.class);

    static final String DAILY_TOKENS = "daily-tokens";
    static final String MONTHLY_TOKENS = "monthly-tokens";
    static final String DAILY_COST = "daily-cost";
    static final String MONTHLY_COST = "monthly-cost";

    /** Reads stored spend per client since a point in time. */
    @FunctionalInterface
    public interface SpendSource {
        Map<String, ClientSpend> since(Instant start);
    }

    private final BudgetProperties properties;
    private final SpendSource source;
    private final Runnable flush;
    private final PriceTable prices;
    private final Clock clock;
    private final ZoneId zone;
    private final Map<String, Budget> clients = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final ReentrantLock refreshLock = new ReentrantLock();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    private volatile State state;

    /**
     * @param flush writes calls still waiting in the tracking queue, so a refresh sees them
     */
    public BudgetLedger(BudgetProperties properties, SpendSource source, Runnable flush, PriceTable prices,
            Clock clock) {
        this.properties = properties;
        this.source = source;
        this.flush = flush;
        this.prices = prices;
        this.clock = clock;
        this.zone = properties.zone();
        clients.putAll(properties.clients());
    }

    /**
     * Lets the call through or throws {@link LimitExceededException}.
     *
     * @return a warning such as {@code daily-cost=85%} when the client has used at least
     *     {@code warn-at} of a budget, otherwise {@code null}
     */
    public String admit(String client) {
        Budget budget = budgetFor(client);
        if (budget == null) {
            return null;
        }
        Instant now = clock.instant();
        State current = current(now);
        ClientSpend local = current.local.getOrDefault(client, ClientSpend.NONE);
        ClientSpend day = current.day.getOrDefault(client, ClientSpend.NONE).plus(local);
        ClientSpend month = current.month.getOrDefault(client, ClientSpend.NONE).plus(local);

        Check check = new Check(client, now);
        check.tokens(MONTHLY_TOKENS, month.tokens(), budget.monthlyTokens(), current.nextMonth);
        check.cost(MONTHLY_COST, month.cost(), budget.monthlyCost(), current.nextMonth);
        check.tokens(DAILY_TOKENS, day.tokens(), budget.dailyTokens(), current.nextDay);
        check.cost(DAILY_COST, day.cost(), budget.dailyCost(), current.nextDay);
        return check.warning();
    }

    @Override
    public void completed(CallOutcome outcome) {
        State current = state;
        if (current == null || budgetFor(outcome.client()) == null) {
            return;
        }
        Usage usage = outcome.usage() == null ? Usage.NONE : outcome.usage();
        BigDecimal cost = prices.find(outcome.provider(), outcome.providerModel(), outcome.startedAt())
                .map(price -> PriceTable.cost(usage, price))
                .orElse(BigDecimal.ZERO);
        if (usage.totalTokens() > 0 || cost.signum() > 0) {
            current.local.merge(outcome.client(), new ClientSpend(usage.totalTokens(), cost), ClientSpend::plus);
        }
    }

    private Budget budgetFor(String client) {
        if (!properties.enabled()) {
            return null;
        }
        Budget budget = clients.getOrDefault(client, properties.defaults());
        return budget.limitsAnything() ? budget : null;
    }

    private State current(Instant now) {
        State current = state;
        if (current == null || !now.isBefore(current.nextDay)) {
            refreshLock.lock();
            try {
                current = state;
                if (current == null || !now.isBefore(current.nextDay)) {
                    current = refresh(now, current);
                }
            } finally {
                refreshLock.unlock();
            }
        } else if (!now.isBefore(current.takenAt.plus(properties.refreshInterval())) && refreshLock.tryLock()) {
            try {
                current = refresh(now, state);
            } finally {
                refreshLock.unlock();
            }
        }
        return current;
    }

    /** Reads stored spend again; callers hold {@link #refreshLock}. */
    private State refresh(Instant now, State previous) {
        LocalDate today = now.atZone(zone).toLocalDate();
        Instant dayStart = today.atStartOfDay(zone).toInstant();
        Instant monthStart = today.withDayOfMonth(1).atStartOfDay(zone).toInstant();
        Instant nextDay = today.plusDays(1).atStartOfDay(zone).toInstant();
        Instant nextMonth = today.withDayOfMonth(1).plusMonths(1).atStartOfDay(zone).toInstant();
        State next;
        try {
            flush.run();
            next = new State(now, nextDay, nextMonth, Map.copyOf(source.since(dayStart)),
                    Map.copyOf(source.since(monthStart)));
        } catch (RuntimeException e) {
            log.warn("could not read stored usage for budgets, using the last known numbers: {}", e.getMessage());
            boolean samePeriod = previous != null && previous.nextDay.equals(nextDay);
            next = new State(now, nextDay, nextMonth,
                    samePeriod ? previous.day : Map.of(),
                    previous != null && previous.nextMonth.equals(nextMonth) ? previous.month : Map.of());
            if (samePeriod) {
                next.local.putAll(previous.local);
            }
        }
        if (previous != null && !previous.nextDay.equals(nextDay)) {
            warned.clear();
        }
        state = next;
        return next;
    }

    /**
     * Stored spend at {@code takenAt} plus what this replica finished since. Calls that end
     * while a refresh runs can miss both until the next refresh.
     */
    private static final class State {

        final Instant takenAt;
        final Instant nextDay;
        final Instant nextMonth;
        final Map<String, ClientSpend> day;
        final Map<String, ClientSpend> month;
        final Map<String, ClientSpend> local = new ConcurrentHashMap<>();

        State(Instant takenAt, Instant nextDay, Instant nextMonth, Map<String, ClientSpend> day,
                Map<String, ClientSpend> month) {
            this.takenAt = takenAt;
            this.nextDay = nextDay;
            this.nextMonth = nextMonth;
            this.day = day;
            this.month = month;
        }
    }

    /** Compares one client's spend with each of its limits. */
    private final class Check {

        private final String client;
        private final Instant now;
        private String warnLimit;
        private double warnShare;

        Check(String client, Instant now) {
            this.client = client;
            this.now = now;
        }

        void tokens(String limit, long spent, Long budget, Instant resetsAt) {
            if (budget != null) {
                compare(limit, spent >= budget, budget == 0 ? 1 : (double) spent / budget, resetsAt,
                        "%d tokens".formatted(budget));
            }
        }

        void cost(String limit, BigDecimal spent, BigDecimal budget, Instant resetsAt) {
            if (budget != null) {
                double share = budget.signum() == 0 ? 1 : spent.divide(budget, 6, RoundingMode.HALF_UP).doubleValue();
                compare(limit, spent.compareTo(budget) >= 0, share, resetsAt,
                        "%s %s".formatted(budget.toPlainString(), prices.currency()));
            }
        }

        private void compare(String limit, boolean exceeded, double share, Instant resetsAt, String amount) {
            if (exceeded) {
                if (warned.add(client + "|" + limit + "|exceeded|" + resetsAt)) {
                    log.warn("client={} has used its {} budget of {}; calls are refused until {}", client, limit,
                            amount, resetsAt);
                }
                throw new LimitExceededException(LimitExceededException.BUDGET_EXCEEDED, limit, client,
                        Duration.between(now, resetsAt), resetsAt,
                        "Client '%s' has used its %s budget of %s".formatted(client, limit, amount));
            }
            if (properties.warnAt() < 1 && share >= properties.warnAt() && share > warnShare) {
                warnShare = share;
                warnLimit = limit;
                if (warned.add(client + "|" + limit + "|warn|" + resetsAt)) {
                    log.info("client={} has used {}% of its {} budget of {}", client, percent(share), limit, amount);
                }
            }
        }

        String warning() {
            return warnLimit == null ? null : warnLimit + "=" + percent(warnShare) + "%";
        }

        private static long percent(double share) {
            return (long) Math.floor(share * 100);
        }
    }
}
