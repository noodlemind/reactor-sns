package io.clype.reactorsns.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the async FIFO SNS publisher.
 *
 * <p>These properties are bound to the {@code sns.publisher} prefix in your
 * application configuration.</p>
 *
 * <p><b>Example Configuration (application.yml):</b></p>
 * <pre>{@code
 * sns:
 *   publisher:
 *     topic-arn: arn:aws:sns:us-east-1:123456789012:MyTopic.fifo
 *     region: us-east-1
 *     partition-count: 256
 *     batch-timeout: 10ms
 *     max-connections: 100
 *     api-call-timeout: 30s
 *     api-call-attempt-timeout: 10s
 *     metrics:
 *       enabled: true
 *     backpressure:
 *       buffer-size: 10000
 *       partition-buffer-size: 100
 * }</pre>
 *
 * @see SnsPublisherAutoConfiguration
 */
@ConfigurationProperties(prefix = "sns.publisher")
public class SnsPublisherProperties {

    private String topicArn;
    private String region;
    private int partitionCount = 256;
    private Duration batchTimeout = Duration.ofMillis(10);
    private int maxConnections = 100;
    private Duration apiCallTimeout = Duration.ofSeconds(30);
    private Duration apiCallAttemptTimeout = Duration.ofSeconds(10);
    private MetricsConfig metrics = new MetricsConfig();
    private BackpressureConfig backpressure = new BackpressureConfig();

    public String getTopicArn() { return topicArn; }
    public void setTopicArn(String topicArn) { this.topicArn = topicArn; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public int getPartitionCount() { return partitionCount; }
    public void setPartitionCount(int partitionCount) { this.partitionCount = partitionCount; }

    public Duration getBatchTimeout() { return batchTimeout; }
    public void setBatchTimeout(Duration batchTimeout) { this.batchTimeout = batchTimeout; }

    public int getMaxConnections() { return maxConnections; }
    public void setMaxConnections(int maxConnections) { this.maxConnections = maxConnections; }

    public Duration getApiCallTimeout() { return apiCallTimeout; }
    public void setApiCallTimeout(Duration apiCallTimeout) { this.apiCallTimeout = apiCallTimeout; }

    public Duration getApiCallAttemptTimeout() { return apiCallAttemptTimeout; }
    public void setApiCallAttemptTimeout(Duration apiCallAttemptTimeout) { this.apiCallAttemptTimeout = apiCallAttemptTimeout; }

    public MetricsConfig getMetrics() { return metrics; }
    public void setMetrics(MetricsConfig metrics) { this.metrics = metrics; }

    public BackpressureConfig getBackpressure() { return backpressure; }
    public void setBackpressure(BackpressureConfig backpressure) { this.backpressure = backpressure; }

    /** Metrics configuration. */
    public static class MetricsConfig {
        private boolean enabled = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    /** Backpressure configuration. */
    public static class BackpressureConfig {
        private int bufferSize = 10_000;
        private int partitionBufferSize = 100;

        public int getBufferSize() { return bufferSize; }
        public void setBufferSize(int bufferSize) { this.bufferSize = bufferSize; }

        public int getPartitionBufferSize() { return partitionBufferSize; }
        public void setPartitionBufferSize(int partitionBufferSize) { this.partitionBufferSize = partitionBufferSize; }
    }
}
