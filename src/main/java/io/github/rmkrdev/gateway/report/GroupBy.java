package io.github.rmkrdev.gateway.report;

import java.util.Arrays;
import java.util.Locale;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Dimension a summary report groups calls by. */
public enum GroupBy {

    CLIENT(ReportRow::client),
    MODEL(ReportRow::model),
    PROVIDER(ReportRow::provider);

    private final Function<ReportRow, String> key;

    GroupBy(Function<ReportRow, String> key) {
        this.key = key;
    }

    String keyOf(ReportRow row) {
        return key.apply(row);
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static GroupBy parse(String value) {
        return Arrays.stream(values())
                .filter(g -> g.id().equals(value == null ? "" : value.trim().toLowerCase(Locale.ROOT)))
                .findFirst()
                .orElseThrow(() -> new InvalidReportException("group_by must be one of " + ids()));
    }

    private static String ids() {
        return Arrays.stream(values()).map(GroupBy::id).collect(Collectors.joining(", "));
    }
}
