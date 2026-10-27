package io.github.rmkrdev.gateway.web;

import io.github.rmkrdev.gateway.report.Percentiles;
import io.github.rmkrdev.gateway.report.Reports;
import io.github.rmkrdev.gateway.report.Stats;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * What the dashboard template renders. Bar sizes are worked out here so the template stays
 * plain markup.
 */
public record DashboardView(
        Reports.Overview overview,
        List<Table> tables,
        List<Bar> bars,
        long peakCalls,
        String firstBucket,
        String lastBucket,
        String rangeLabel) {

    /** Matches the SVG {@code viewBox} in {@code dashboard.html}. */
    static final int CHART_WIDTH = 960;
    static final int CHART_HEIGHT = 120;

    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("MMM d HH:mm");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MMM d");
    private static final DateTimeFormatter FULL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** One table of groups with bar widths in percent of the largest value in the table. */
    public record Table(String title, List<Row> rows) {
    }

    public record Row(String label, Stats stats, int costPercent, int latencyPercent) {
    }

    /** One bucket of the calls chart, in SVG user units. */
    public record Bar(double x, double width, double callsHeight, double errorsHeight, String title) {
    }

    static DashboardView of(Reports.Overview overview) {
        List<Table> tables = List.of(
                table("By client", overview.byClient()),
                table("By model", overview.byModel()),
                table("By provider", overview.byProvider()));
        DateTimeFormatter format = "hour".equals(overview.bucket()) ? HOUR : DAY;
        List<Reports.Point> series = overview.series();
        long peak = series.stream().mapToLong(p -> p.stats().calls()).max().orElse(0);
        double width = series.isEmpty() ? 0 : (double) CHART_WIDTH / series.size();
        List<Bar> bars = new ArrayList<>(series.size());
        for (int i = 0; i < series.size(); i++) {
            Reports.Point point = series.get(i);
            Stats stats = point.stats();
            bars.add(new Bar(round(i * width), round(Math.max(1, width - 1)),
                    height(stats.calls(), peak), height(stats.errors(), peak),
                    point.start().format(format) + ": " + stats.calls() + " calls, " + stats.errors() + " errors"));
        }
        String first = series.isEmpty() ? "" : series.getFirst().start().format(format);
        String last = series.isEmpty() ? "" : series.getLast().start().format(format);
        ZoneId zone = ZoneId.of(overview.zone());
        String range = FULL.format(overview.from().atZone(zone)) + " to " + FULL.format(overview.to().atZone(zone))
                + " " + overview.zone();
        return new DashboardView(overview, tables, bars, peak, first, last, range);
    }

    private static Table table(String title, List<Reports.Group> groups) {
        double maxCost = max(groups, s -> s.cost().doubleValue());
        double maxLatency = max(groups, s -> p95(s.totalMs()));
        List<Row> rows = groups.stream()
                .map(g -> new Row(g.key() == null ? "no provider" : g.key(), g.stats(),
                        percent(g.stats().cost().doubleValue(), maxCost), percent(p95(g.stats().totalMs()), maxLatency)))
                .toList();
        return new Table(title, rows);
    }

    private static double max(List<Reports.Group> groups, ToDoubleFunction<Stats> value) {
        return groups.stream().map(Reports.Group::stats).mapToDouble(value).max().orElse(0);
    }

    private static double p95(Percentiles percentiles) {
        return percentiles == null ? 0 : percentiles.p95();
    }

    private static int percent(double value, double max) {
        return max <= 0 ? 0 : (int) Math.round(value * 100 / max);
    }

    private static double height(long value, long peak) {
        return peak == 0 ? 0 : round((double) value * CHART_HEIGHT / peak);
    }

    private static double round(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
