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

    public static final int DEFAULT_PARTITION_COUNT = 256;
    public static final Duration DEFAULT_BATCH_TIMEOUT = Duration.ofMillis(10);
    public static final int DEFAULT_MAX_CONNECTIONS = 100;
    public static final int DEFAULT_BUFFER_SIZE = 10_000;
    public static final int DEFAULT_PARTITION_BUFFER_SIZE = 100;
    public static final int DEFAULT_REQUESTS_PER_SECOND = 2500;
    public static final int DEFAULT_MESSAGES_PER_GROUP_PER_SECOND = 250;
    public static final int DEFAULT_MIN_THREAD_POOL_SIZE = 8;

    private String topicArn;
    private String region;
    private int partitionCount = DEFAULT_PARTITION_COUNT;
    private Duration batchTimeout = DEFAULT_BATCH_TIMEOUT;
    private int maxConnections = DEFAULT_MAX_CONNECTIONS;
    private MetricsConfig metrics = new MetricsConfig();
    private BackpressureConfig backpressure = new BackpressureConfig();
    private RateLimitConfig rateLimit = new RateLimitConfig();

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

    public MetricsConfig getMetrics() { return metrics; }
    public void setMetrics(MetricsConfig metrics) { this.metrics = metrics; }

    public BackpressureConfig getBackpressure() { return backpressure; }
    public void setBackpressure(BackpressureConfig backpressure) { this.backpressure = backpressure; }

    public RateLimitConfig getRateLimit() { return rateLimit; }
    public void setRateLimit(RateLimitConfig rateLimit) { this.rateLimit = rateLimit; }

    /** Metrics configuration. */
    public static class MetricsConfig {
        private boolean enabled = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    /** Backpressure configuration. */
    public static class BackpressureConfig {
        private int bufferSize = DEFAULT_BUFFER_SIZE;
        private int partitionBufferSize = DEFAULT_PARTITION_BUFFER_SIZE;

        public int getBufferSize() { return bufferSize; }
        public void setBufferSize(int bufferSize) { this.bufferSize = bufferSize; }

        public int getPartitionBufferSize() { return partitionBufferSize; }
        public void setPartitionBufferSize(int partitionBufferSize) { this.partitionBufferSize = partitionBufferSize; }
    }

    /**
     * Rate limiting configuration for proactive throttling prevention.
     *
     * <p>When enabled, enforces token bucket rate limiting at both topic and message group levels
     * to prevent AWS SNS FIFO throttling errors (HTTP 429 / ThrottlingException).</p>
     */
    public static class RateLimitConfig {
        private boolean enabled = false;
        private int requestsPerSecond = DEFAULT_REQUESTS_PER_SECOND;
        private int messagesPerGroupPerSecond = DEFAULT_MESSAGES_PER_GROUP_PER_SECOND;
        private Duration warmupPeriod = Duration.ZERO;
        private int threadPoolSize = Math.max(DEFAULT_MIN_THREAD_POOL_SIZE, Runtime.getRuntime().availableProcessors());

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public int getRequestsPerSecond() { return requestsPerSecond; }
        public void setRequestsPerSecond(int requestsPerSecond) { this.requestsPerSecond = requestsPerSecond; }

        public int getMessagesPerGroupPerSecond() { return messagesPerGroupPerSecond; }
        public void setMessagesPerGroupPerSecond(int messagesPerGroupPerSecond) { this.messagesPerGroupPerSecond = messagesPerGroupPerSecond; }

        public Duration getWarmupPeriod() { return warmupPeriod; }
        public void setWarmupPeriod(Duration warmupPeriod) { this.warmupPeriod = warmupPeriod; }

        public int getThreadPoolSize() { return threadPoolSize; }
        public void setThreadPoolSize(int threadPoolSize) { this.threadPoolSize = threadPoolSize; }
    }
}
