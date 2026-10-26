package io.github.rmkrdev.gateway.tracking;

import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Wires built-in cost and latency tracking. Turn it off with {@code gateway.tracking.enabled=false}. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "gateway.tracking.enabled", havingValue = "true", matchIfMissing = true)
@EnableScheduling
class TrackingConfiguration {

    @Bean
    PriceTable priceTable(PricingProperties pricing) {
        return new PriceTable(pricing);
    }

    @Bean
    CallStore callStore(JdbcTemplate jdbc) {
        return new CallStore(jdbc);
    }

    @Bean
    CallRecorder callRecorder(CallStore store, PriceTable prices, TrackingProperties properties) {
        return new CallRecorder(store, prices, properties);
    }

    @Bean
    RetentionPurge retentionPurge(CallStore store, TrackingProperties properties) {
        return new RetentionPurge(store, properties);
    }

    /** Deletes calls older than {@code gateway.tracking.retention}. */
    static class RetentionPurge {

        private static final Logger log = LoggerFactory.getLogger(RetentionPurge.class);

        private final CallStore store;
        private final Duration retention;

        RetentionPurge(CallStore store, TrackingProperties properties) {
            this.store = store;
            this.retention = properties.retention();
        }

        @Scheduled(initialDelayString = "${gateway.tracking.purge-interval:1h}",
                fixedDelayString = "${gateway.tracking.purge-interval:1h}")
        void purge() {
            if (retention.isZero() || retention.isNegative()) {
                return;
            }
            int deleted = store.deleteStartedBefore(Instant.now().minus(retention));
            if (deleted > 0) {
                log.info("deleted {} calls older than {}", deleted, retention);
            }
        }
    }
}
