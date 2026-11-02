package io.github.rmkrdev.gateway.pii;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class PlaceholderRestorerTests {

    private final PlaceholderRestorer restorer = new PlaceholderRestorer(Map.of(
            "[EMAIL_1]", "priya@example.com",
            "[PHONE_12]", "+91 98765 43210"));

    @Test
    void restoresOnlyItsOwnPlaceholders() {
        assertThat(restorer.restore("Mail [EMAIL_1] or [EMAIL_2], see [1] and [PHONE_12]."))
                .isEqualTo("Mail priya@example.com or [EMAIL_2], see [1] and +91 98765 43210.");
        assertThat(restorer.restore("nothing here")).isEqualTo("nothing here");
        assertThat(restorer.restore(null)).isNull();
    }

    @Test
    void holdsBackAPlaceholderSplitAcrossChunks() {
        StringBuilder out = new StringBuilder();
        for (String chunk : new String[] {"Reach her at [EM", "AIL", "_1", "] or [PHO", "NE_12] today [", "x]"}) {
            out.append(restorer.next(chunk));
        }
        out.append(restorer.finish());
        assertThat(out).hasToString("Reach her at priya@example.com or +91 98765 43210 today [x]");
    }

    @Test
    void neverHoldsOrdinaryBrackets() {
        assertThat(restorer.next("array[i")).isEqualTo("array[i");
        assertThat(restorer.next("see [this is long text")).isEqualTo("see [this is long text");
        assertThat(restorer.next("end [EMA")).isEqualTo("end ");
        assertThat(restorer.finish()).isEqualTo("[EMA");
        assertThat(restorer.finish()).isEmpty();
    }
}
