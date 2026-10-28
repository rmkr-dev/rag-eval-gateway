package io.github.rmkrdev.gateway.report;

import io.github.rmkrdev.gateway.tracking.PricingProperties;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wires the reports API. Needs tracking and is off until {@code gateway.reports.enabled=true},
 * because nothing protects it until Entra auth arrives.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty(name = "gateway.tracking.enabled", matchIfMissing = true)
@ConditionalOnBooleanProperty("gateway.reports.enabled")
class ReportsConfiguration {

    @Bean
    ReportService reportService(JdbcTemplate jdbc, PlatformTransactionManager transactions,
            PricingProperties pricing, ReportProperties properties) {
        TransactionTemplate readOnly = new TransactionTemplate(transactions);
        readOnly.setReadOnly(true);
        return new ReportService(new ReportStore(jdbc, readOnly), pricing.currency(), properties.maxRows());
    }

    @Bean
    ReportRequests reportRequests(ReportProperties properties) {
        return new ReportRequests(properties, Clock.systemUTC());
    }
}
