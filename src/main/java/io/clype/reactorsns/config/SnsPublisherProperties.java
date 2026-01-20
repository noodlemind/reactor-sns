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

    /**
     * Creates a new SnsPublisherProperties with default values.
     */
    public SnsPublisherProperties() {
    }

    /**
     * Returns the SNS topic ARN.
     *
     * @return the SNS topic ARN
     */
    public String getTopicArn() {
        return topicArn;
    }

    /**
     * Sets the SNS topic ARN.
     *
     * @param topicArn the SNS topic ARN
     */
    public void setTopicArn(String topicArn) {
        this.topicArn = topicArn;
    }

    /**
     * Returns the AWS region.
     *
     * @return the AWS region
     */
    public String getRegion() {
        return region;
    }

    /**
     * Sets the AWS region.
     *
     * @param region the AWS region
     */
    public void setRegion(String region) {
        this.region = region;
    }

    /**
     * Returns the partition count.
     *
     * @return the partition count
     */
    public int getPartitionCount() {
        return partitionCount;
    }

    /**
     * Sets the partition count.
     *
     * @param partitionCount the partition count
     */
    public void setPartitionCount(int partitionCount) {
        this.partitionCount = partitionCount;
    }

    /**
     * Returns the batch timeout.
     *
     * @return the batch timeout
     */
    public Duration getBatchTimeout() {
        return batchTimeout;
    }

    /**
     * Sets the batch timeout.
     *
     * @param batchTimeout the batch timeout
     */
    public void setBatchTimeout(Duration batchTimeout) {
        this.batchTimeout = batchTimeout;
    }
}
