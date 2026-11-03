package io.github.rmkrdev.gateway.eval;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rmkrdev.gateway.config.GatewayProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Binding, validation and sink wiring; the whole path runs in the endpoint tests. */
class EvalConfigurationTests {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({EvalProperties.class, GatewayProperties.class})
    @Import(EvalConfiguration.class)
    static class Config {

        @Bean
        JsonMapper jsonMapper() {
            return JsonMapper.builder().build();
        }

        @Bean
        JdbcTemplate jdbcTemplate() {
            return new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource());
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ExtraSink {

        @Bean
        EvalSink auditSink() {
            return samples -> {
            };
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(Config.class)
            .withPropertyValues("gateway.models.rag.targets[0].provider=p", "gateway.models.rag.targets[0].model=m",
                    "gateway.providers.p.base-url=http://localhost:1");

    @Test
    void offByDefault() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(EvalStore.class);
            EvalProperties properties = context.getBean(EvalProperties.class);
            assertThat(properties.enabled()).isFalse();
            assertThat(properties.sampleRate()).isEqualTo(0.01);
            assertThat(properties.sinks()).containsExactly("database");
            assertThat(properties.harness().faithfulness().metrics()).containsExactly("token_overlap");
            assertThat(properties.harness().faithfulness().threshold()).isEqualTo(0.3);
            assertThat(properties.harness().relevance().threshold()).isEqualTo(0.1);
            EvalSampler sampler = context.getBean(EvalSampler.class);
            assertThat(sampler.enabled()).isFalse();
            assertThat(sampler.shouldSample("anyone", "rag")).isFalse();
        });
    }

    @Test
    void bindsRatesTagsSinksAndHarnessChecks() {
        runner.withUserConfiguration(ExtraSink.class)
                .withPropertyValues("gateway.eval.enabled=true", "gateway.eval.sample-rate=0.05",
                        "gateway.eval.models.rag=0.5", "gateway.eval.clients.search-api=1",
                        "gateway.eval.tags=prod,westeurope", "gateway.eval.sinks=database,file",
                        "gateway.eval.file.format=fixtures",
                        "gateway.eval.harness.faithfulness.metrics=contains_all",
                        "gateway.eval.harness.relevance.threshold=0.25")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(EvalStore.class);
                    EvalProperties properties = context.getBean(EvalProperties.class);
                    assertThat(properties.tags()).containsExactly("prod", "westeurope");
                    assertThat(properties.file().format()).isEqualTo(EvalProperties.FileFormat.FIXTURES);
                    assertThat(properties.harness().faithfulness().metrics()).containsExactly("contains_all");
                    assertThat(properties.harness().faithfulness().threshold()).isEqualTo(0.3);
                    assertThat(properties.harness().relevance().threshold()).isEqualTo(0.25);
                    EvalSampler sampler = context.getBean(EvalSampler.class);
                    assertThat(sampler.rateFor("SEARCH-API", "chat")).isEqualTo(1.0);
                    assertThat(sampler.rateFor("other", "rag")).isEqualTo(0.5);
                    assertThat(sampler.sinks()).hasSize(3);
                    assertThat(sampler.sinks().subList(0, 2)).extracting(EvalSink::name)
                            .containsExactly("database", "file");
                });
    }

    @Test
    void onlyBeanSinksWhenBuiltInsAreTurnedOff() {
        runner.withUserConfiguration(ExtraSink.class)
                .withPropertyValues("gateway.eval.enabled=true", "gateway.eval.sinks=")
                .run(context -> assertThat(context.getBean(EvalSampler.class).sinks()).hasSize(1));
    }

    @Test
    void failsOnUnknownSinksAliasesAndNowhereToWrite() {
        runner.withPropertyValues("gateway.eval.enabled=true", "gateway.eval.sinks=kafka",
                        "gateway.eval.models.missing=0.5")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("unknown sink 'kafka'")
                        .hasMessageContaining("unknown model alias 'missing'")
                        .hasMessageContaining("nowhere to go"));
    }

    @Test
    void failsOnRatesOutsideZeroToOne() {
        runner.withPropertyValues("gateway.eval.sample-rate=1.5")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("gateway.eval.clients.a=-0.1")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void knowsEveryHarnessMetric() {
        assertThat(EvalConfiguration.HARNESS_METRICS).hasSize(13).contains("token_overlap", "edit_ratio");
        assertThat(List.copyOf(EvalConfiguration.HARNESS_METRICS)).doesNotContain("faithfulness");
    }
}
