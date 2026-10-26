package io.github.rmkrdev.gateway.tracking;

import io.github.rmkrdev.gateway.api.Usage;
import io.github.rmkrdev.gateway.tracking.PricingProperties.Price;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Finds the price for a call and turns token counts into money. */
public class PriceTable {

    static final String ANY_MODEL = "*";
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);
    private static final int SCALE = 8;

    private final String currency;
    private final List<Price> prices;

    public PriceTable(PricingProperties properties) {
        this.currency = properties.currency();
        this.prices = List.copyOf(properties.prices());
    }

    public String currency() {
        return currency;
    }

    /**
     * The price in force on the day of {@code at}: an exact model entry wins over a {@code *}
     * entry, and among those the latest {@code effective-from} not after that day.
     */
    public Optional<Price> find(String provider, String model, Instant at) {
        if (provider == null || model == null) {
            return Optional.empty();
        }
        LocalDate day = at.atZone(ZoneOffset.UTC).toLocalDate();
        Optional<Price> exact = latest(provider, model, day);
        return exact.isPresent() ? exact : latest(provider, ANY_MODEL, day);
    }

    private Optional<Price> latest(String provider, String model, LocalDate day) {
        return prices.stream()
                .filter(p -> p.provider().equals(provider) && p.model().equals(model))
                .filter(p -> p.effectiveFrom() == null || !p.effectiveFrom().isAfter(day))
                .max(Comparator.comparing(p -> p.effectiveFrom() == null ? LocalDate.MIN : p.effectiveFrom()));
    }

    /**
     * Cost of {@code usage} at {@code price}. Prompt tokens include cache reads and writes, so
     * those are taken out of the input count and charged at their own rates.
     */
    public static BigDecimal cost(Usage usage, Price price) {
        long cacheRead = nonNegative(usage.cacheReadTokens());
        long cacheWrite = nonNegative(usage.cacheWriteTokens());
        long uncached = Math.max(0, usage.promptTokens() - cacheRead - cacheWrite);
        BigDecimal perMillion = price.input().multiply(BigDecimal.valueOf(uncached))
                .add(price.cacheRead().multiply(BigDecimal.valueOf(cacheRead)))
                .add(price.cacheWrite().multiply(BigDecimal.valueOf(cacheWrite)))
                .add(price.output().multiply(BigDecimal.valueOf(Math.max(0, usage.completionTokens()))));
        return perMillion.divide(MILLION, SCALE, RoundingMode.HALF_UP);
    }

    private static long nonNegative(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }
}
