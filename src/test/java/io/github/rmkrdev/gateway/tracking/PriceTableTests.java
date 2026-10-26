package io.github.rmkrdev.gateway.tracking;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rmkrdev.gateway.api.Usage;
import io.github.rmkrdev.gateway.tracking.PricingProperties.Price;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class PriceTableTests {

    private static Price price(String provider, String model, String input, String output, LocalDate from) {
        return new Price(provider, model, new BigDecimal(input), new BigDecimal(output), null, null, from);
    }

    private final PriceTable table = new PriceTable(new PricingProperties("USD", List.of(
            price("openai", "gpt-4o-mini", "0.15", "0.60", null),
            price("openai", "gpt-4o-mini", "0.10", "0.40", LocalDate.parse("2026-11-01")),
            price("local", "*", "0", "0", null),
            price("openai", "*", "5", "15", null))));

    @Test
    void exactModelBeatsWildcard() {
        assertThat(table.find("openai", "gpt-4o-mini", Instant.parse("2026-10-26T10:00:00Z")))
                .get().extracting(Price::input).isEqualTo(new BigDecimal("0.15"));
        assertThat(table.find("openai", "gpt-4.1", Instant.parse("2026-10-26T10:00:00Z")))
                .get().extracting(Price::input).isEqualTo(new BigDecimal("5"));
    }

    @Test
    void laterPriceAppliesFromItsUtcDay() {
        assertThat(table.find("openai", "gpt-4o-mini", Instant.parse("2026-10-31T23:59:59Z")))
                .get().extracting(Price::version).isEqualTo("initial");
        assertThat(table.find("openai", "gpt-4o-mini", Instant.parse("2026-11-01T00:00:00Z")))
                .get().extracting(Price::version).isEqualTo("2026-11-01");
    }

    @Test
    void unknownProviderOrMissingTargetHasNoPrice() {
        assertThat(table.find("anthropic", "claude-sonnet-4-5", Instant.now())).isEmpty();
        assertThat(table.find(null, null, Instant.now())).isEmpty();
    }

    @Test
    void costsUncachedCachedAndCompletionTokensSeparately() {
        Price price = new Price("anthropic", "claude-sonnet-4-5", new BigDecimal("3"), new BigDecimal("15"),
                new BigDecimal("0.30"), new BigDecimal("3.75"), null);
        // 10,000 prompt tokens of which 6,000 cache reads and 1,000 cache writes, 2,000 completion
        Usage usage = new Usage(10_000, 2_000, 6_000, 1_000);

        // 3,000 x 3 + 6,000 x 0.30 + 1,000 x 3.75 + 2,000 x 15 = 44,550 per million
        assertThat(PriceTable.cost(usage, price)).isEqualByComparingTo("0.04455");
    }

    @Test
    void cacheRatesDefaultToInputRate() {
        Price price = price("openai", "gpt-4o-mini", "0.15", "0.60", null);

        assertThat(price.cacheRead()).isEqualByComparingTo("0.15");
        assertThat(PriceTable.cost(new Usage(1_000_000, 1_000_000, 400_000, null), price))
                .isEqualByComparingTo("0.75");
    }

    @Test
    void noTokensCostNothing() {
        assertThat(PriceTable.cost(Usage.NONE, price("local", "*", "1", "1", null))).isEqualByComparingTo("0");
    }
}
