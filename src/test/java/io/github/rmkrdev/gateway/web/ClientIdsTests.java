package io.github.rmkrdev.gateway.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ClientIdsTests {

    @Test
    void keepsWellFormedIds() {
        assertThat(ClientIds.of("billing-api")).isEqualTo("billing-api");
        assertThat(ClientIds.of("  team.search:prod ")).isEqualTo("team.search:prod");
    }

    @Test
    void missingOrMalformedIdsAreAnonymous() {
        assertThat(ClientIds.of(null)).isEqualTo(ClientIds.ANONYMOUS);
        assertThat(ClientIds.of("")).isEqualTo(ClientIds.ANONYMOUS);
        assertThat(ClientIds.of("drop table;")).isEqualTo(ClientIds.ANONYMOUS);
        assertThat(ClientIds.of("x".repeat(65))).isEqualTo(ClientIds.ANONYMOUS);
    }
}
