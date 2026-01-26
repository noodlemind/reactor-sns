package io.clype.reactorsns.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import io.clype.reactorsns.metrics.SnsPublisherMetrics;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Auto-configuration for SNS publisher metrics.
 *
 * <p>This configuration is automatically enabled when:</p>
 * <ul>
 *   <li>Micrometer is on the classpath</li>
 *   <li>A {@link MeterRegistry} bean exists</li>
 *   <li>The {@code sns.publisher.metrics.enabled} property is true (default)</li>
 * </ul>
 *
 * <p><b>Disabling Metrics:</b></p>
 * <pre>{@code
 * sns:
 *   publisher:
 *     metrics:
 *       enabled: false
 * }</pre>
 *
 * @see SnsPublisherMetrics
 */
@AutoConfiguration(after = SnsPublisherAutoConfiguration.class)
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnBean(MeterRegistry.class)
@ConditionalOnProperty(prefix = "sns.publisher.metrics", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class SnsPublisherMetricsAutoConfiguration {

    /**
     * Creates the SNS publisher metrics bean.
     *
     * @param registry   the Micrometer meter registry
     * @param properties the SNS publisher configuration properties
     * @return the configured metrics instance
     */
    @Bean
    @ConditionalOnMissingBean
    public SnsPublisherMetrics snsPublisherMetrics(
            MeterRegistry registry,
            SnsPublisherProperties properties) {
        return new SnsPublisherMetrics(registry, properties.getTopicArn());
    }
}
