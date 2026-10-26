package io.github.rmkrdev.gateway.tracking;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Price table used to cost every call. Prices are per million tokens.
 *
 * <pre>
 * gateway:
 *   pricing:
 *     currency: USD
 *     prices:
 *       - provider: openai
 *         model: gpt-4o-mini
 *         input: 0.15
 *         output: 0.60
 *         cache-read: 0.075
 *       - provider: local
 *         model: "*"
 *         input: 0
 *         output: 0
 * </pre>
 *
 * @param currency ISO 4217 code stored with every cost
 * @param prices price entries; a later {@code effective-from} replaces an earlier one for the
 *     same provider and model from that day on
 */
@Validated
@ConfigurationProperties("gateway.pricing")
public record PricingProperties(
        @NotNull @Pattern(regexp = "[A-Z]{3}") @DefaultValue("USD") String currency,
        @DefaultValue List<@Valid Price> prices) {

    /**
     * Price of one model on one provider.
     *
     * @param provider provider id from {@code gateway.providers}
     * @param model model name on that provider, or {@code *} for any model without its own entry
     * @param input price per million uncached prompt tokens
     * @param output price per million completion tokens, reasoning tokens included
     * @param cacheRead price per million prompt tokens read from the provider cache; defaults
     *     to {@code input}
     * @param cacheWrite price per million prompt tokens written to the provider cache; defaults
     *     to {@code input}
     * @param effectiveFrom first UTC day this price applies; entries without it apply from the
     *     beginning
     */
    public record Price(
            @NotBlank String provider,
            @NotBlank String model,
            @NotNull @DecimalMin("0") BigDecimal input,
            @NotNull @DecimalMin("0") BigDecimal output,
            @DecimalMin("0") BigDecimal cacheRead,
            @DecimalMin("0") BigDecimal cacheWrite,
            LocalDate effectiveFrom) {

        public Price {
            if (cacheRead == null) {
                cacheRead = input;
            }
            if (cacheWrite == null) {
                cacheWrite = input;
            }
        }

        /** Label stored with each call so a cost can be traced back to the price that made it. */
        public String version() {
            return effectiveFrom == null ? "initial" : effectiveFrom.toString();
        }
    }
}
