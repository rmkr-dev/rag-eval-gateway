package io.github.rmkrdev.gateway.report;

import io.github.rmkrdev.gateway.tracking.PricingProperties;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wires the reports API. Needs tracking; turn reports off with
 * {@code gateway.reports.enabled=false}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = {"gateway.tracking.enabled", "gateway.reports.enabled"}, havingValue = "true",
        matchIfMissing = true)
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
