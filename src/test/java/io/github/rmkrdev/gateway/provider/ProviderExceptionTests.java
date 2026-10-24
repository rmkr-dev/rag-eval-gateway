package io.github.rmkrdev.gateway.provider;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rmkrdev.gateway.provider.ProviderException.Failure;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ProviderExceptionTests {

    @ParameterizedTest
    @CsvSource({
            "408, TRANSIENT", "425, TRANSIENT", "429, TRANSIENT", "500, TRANSIENT", "502, TRANSIENT", "504, TRANSIENT",
            "400, REQUEST", "413, REQUEST", "422, REQUEST",
            "401, PROVIDER", "403, PROVIDER", "404, PROVIDER", "409, PROVIDER"
    })
    void classifiesStatusCodes(int status, Failure expected) {
        assertThat(ProviderException.classify(status)).isEqualTo(expected);
    }
}
