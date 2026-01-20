package com.example.snspublisher.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

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
 * }</pre>
 *
 * @see SnsPublisherAutoConfiguration
 */
@ConfigurationProperties(prefix = "sns.publisher")
public class SnsPublisherProperties {

    /**
     * The ARN of the SNS topic to publish to.
     */
    private String topicArn;

    /**
     * AWS Region for the SNS client.
     * Default: null (Uses AWS Default Region Provider Chain)
     */
    private String region;

    /**
     * Number of logical partitions to ensure FIFO ordering.
     * Default: 256
     */
    private int partitionCount = 256;

    /**
     * Timeout for buffering batch messages before sending.
     * Default: 10ms
     */
    private Duration batchTimeout = Duration.ofMillis(10);

    public String getTopicArn() {
        return topicArn;
    }

    public void setTopicArn(String topicArn) {
        this.topicArn = topicArn;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public int getPartitionCount() {
        return partitionCount;
    }

    public void setPartitionCount(int partitionCount) {
        this.partitionCount = partitionCount;
    }

    public Duration getBatchTimeout() {
        return batchTimeout;
    }

    public void setBatchTimeout(Duration batchTimeout) {
        this.batchTimeout = batchTimeout;
    }
}
