package io.github.rmkrdev.gateway.pii;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rmkrdev.gateway.config.GatewayProperties;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Binding, defaults and startup checks. The request path runs in the endpoint tests. */
class PiiConfigurationTests {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({PiiProperties.class, GatewayProperties.class})
    @Import(PiiConfiguration.class)
    static class Config {
    }

    @Configuration(proxyBeanMethods = false)
    static class TicketDetector {

        @Bean
        PiiDetector ticketDetector() {
            return new PatternDetector("ticket", "TICKET", Pattern.compile("TCK-\\d{5}"));
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(Config.class)
            .withPropertyValues(
                    "gateway.providers.p.base-url=http://localhost:1",
                    "gateway.models.chat.targets[0].provider=p",
                    "gateway.models.chat.targets[0].model=m");

    @Test
    void offByDefaultWithEveryBuiltInReady() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            PiiProperties properties = context.getBean(PiiProperties.class);
            assertThat(properties.mode()).isEqualTo(PiiMode.OFF);
            assertThat(properties.active()).isFalse();
            assertThat(properties.restore()).isFalse();
            assertThat(properties.scrubLogs()).isTrue();
            PiiRedactor redactor = context.getBean(PiiRedactor.class);
            assertThat(redactor.modeFor("anyone", "chat")).isEqualTo(PiiMode.OFF);
            assertThat(redactor.detectors()).extracting(PiiDetector::id)
                    .containsExactlyElementsOf(BuiltInDetectors.all().stream().map(PiiDetector::id).toList());
        });
    }

    @Test
    void bindsModesCustomDetectorsAndBeansInOrder() {
        runner.withUserConfiguration(TicketDetector.class).withPropertyValues(
                        "gateway.pii.mode=redact",
                        "gateway.pii.models.chat=block",
                        "gateway.pii.clients.batch-indexer=false",
                        "gateway.pii.custom.employee-id.pattern=EMP-\\d{6}",
                        "gateway.pii.restore=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    PiiProperties properties = context.getBean(PiiProperties.class);
                    assertThat(properties.active()).isTrue();
                    assertThat(properties.clients()).containsEntry("batch-indexer", PiiMode.OFF);
                    PiiRedactor redactor = context.getBean(PiiRedactor.class);
                    assertThat(redactor.detectors()).extracting(PiiDetector::id)
                            .startsWith("employee-id", "ticket", "credit-card");
                    assertThat(redactor.detectors().get(0).label()).isEqualTo("EMPLOYEE_ID");
                    assertThat(redactor.modeFor("batch-indexer", "other")).isEqualTo(PiiMode.OFF);
                    assertThat(redactor.modeFor("batch-indexer", "chat")).isEqualTo(PiiMode.BLOCK);
                });
    }

    @Test
    void detectorsCanBeNarrowedDown() {
        runner.withPropertyValues("gateway.pii.detectors=email,phone").run(context ->
                assertThat(context.getBean(PiiRedactor.class).detectors()).extracting(PiiDetector::id)
                        .containsExactly("email", "phone"));
    }

    @Test
    void startupFailsOnBadConfiguration() {
        runner.withUserConfiguration(TicketDetector.class).withPropertyValues(
                        "gateway.pii.detectors=email,passport",
                        "gateway.pii.models.missing=redact",
                        "gateway.pii.custom.broken.pattern=EMP-(\\d",
                        "gateway.pii.custom.empty.pattern=\\d*",
                        "gateway.pii.custom.lower.pattern=x\\d+",
                        "gateway.pii.custom.lower.label=lower",
                        "gateway.pii.custom.ticket.pattern=T\\d+")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause().message()
                            .contains("unknown detector 'passport'")
                            .contains("unknown model alias 'missing'")
                            .contains("custom.broken pattern does not compile")
                            .contains("custom.empty pattern matches empty text")
                            .contains("label 'lower'")
                            .contains("'ticket' is used twice");
                });
    }

    @Test
    void unknownModesAreRejected() {
        runner.withPropertyValues("gateway.pii.mode=scramble").run(context -> assertThat(context).hasFailed());
    }
}
