package io.github.rmkrdev.gateway.report;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Builds reports from stored calls. Each report is one bounded scan of the range: counts, tokens
 * and cost are summed and latencies are kept per group so percentiles are exact on any
 * database.
 */
public class ReportService {

    /** Most points one series may have; a month of hours fits. */
    static final int MAX_POINTS = 1000;

    private final ReportStore store;
    private final String currency;
    private final int maxRows;

    ReportService(ReportStore store, String currency, int maxRows) {
        this.store = store;
        this.currency = currency;
        this.maxRows = maxRows;
    }

    public String currency() {
        return currency;
    }

    public Reports.Summary summary(ReportQuery query, GroupBy groupBy) {
        StatsAccumulator totals = new StatsAccumulator(currency);
        Grouping groups = new Grouping(groupBy);
        scan(query, row -> {
            totals.add(row);
            groups.add(row);
        });
        return new Reports.Summary(query.from(), query.to(), query.zone().getId(), groupBy.id(), currency,
                totals.stats(), groups.result());
    }

    public Reports.Series series(ReportQuery query, Bucket bucket) {
        Timeline timeline = new Timeline(query, bucket);
        scan(query, timeline::add);
        return new Reports.Series(query.from(), query.to(), query.zone().getId(), bucket.id(), currency,
                timeline.result());
    }

    public Reports.Overview overview(ReportQuery query, Bucket bucket) {
        StatsAccumulator totals = new StatsAccumulator(currency);
        Grouping clients = new Grouping(GroupBy.CLIENT);
        Grouping models = new Grouping(GroupBy.MODEL);
        Grouping providers = new Grouping(GroupBy.PROVIDER);
        Timeline timeline = new Timeline(query, bucket);
        scan(query, row -> {
            totals.add(row);
            clients.add(row);
            models.add(row);
            providers.add(row);
            timeline.add(row);
        });
        return new Reports.Overview(query.from(), query.to(), query.zone().getId(), currency, totals.stats(),
                clients.result(), models.result(), providers.result(), bucket.id(), timeline.result());
    }

    private void scan(ReportQuery query, Consumer<ReportRow> consumer) {
        long count = store.count(query);
        if (count > maxRows) {
            throw new InvalidReportException("the range holds " + count + " calls, more than the "
                    + maxRows + " one report may read (gateway.reports.max-rows); narrow the range or filter");
        }
        store.forEach(query, consumer);
    }

    private final class Grouping {

        private final GroupBy groupBy;
        private final Map<String, StatsAccumulator> groups = new HashMap<>();

        Grouping(GroupBy groupBy) {
            this.groupBy = groupBy;
        }

        void add(ReportRow row) {
            groups.computeIfAbsent(groupBy.keyOf(row), k -> new StatsAccumulator(currency)).add(row);
        }

        List<Reports.Group> result() {
            return groups.entrySet().stream()
                    .sorted(Comparator.<Map.Entry<String, StatsAccumulator>>comparingLong(e -> -e.getValue().calls())
                            .thenComparing(Map.Entry::getKey, Comparator.nullsLast(Comparator.naturalOrder())))
                    .map(e -> new Reports.Group(e.getKey(), e.getValue().stats()))
                    .toList();
        }
    }

    private final class Timeline {

        private final ReportQuery query;
        private final Bucket bucket;
        private final TreeMap<ZonedDateTime, StatsAccumulator> points = new TreeMap<>();

        Timeline(ReportQuery query, Bucket bucket) {
            this.query = query;
            this.bucket = bucket;
            ZonedDateTime end = query.to().atZone(query.zone());
            for (ZonedDateTime start = bucket.start(query.from(), query.zone()); start.isBefore(end);
                    start = bucket.next(start)) {
                if (points.size() == MAX_POINTS) {
                    throw new InvalidReportException("the range needs more than " + MAX_POINTS + " "
                            + bucket.id() + " buckets; use a wider bucket or a shorter range");
                }
                points.put(start, new StatsAccumulator(currency));
            }
        }

        void add(ReportRow row) {
            StatsAccumulator point = points.get(bucket.start(row.startedAt(), query.zone()));
            if (point != null) {
                point.add(row);
            }
        }

        List<Reports.Point> result() {
            List<Reports.Point> result = new ArrayList<>(points.size());
            points.forEach((start, stats) -> result.add(new Reports.Point(start.toOffsetDateTime(), stats.stats())));
            return result;
        }
    }
}
