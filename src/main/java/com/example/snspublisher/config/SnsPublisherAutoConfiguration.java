package com.example.snspublisher.config;

import com.example.snspublisher.service.AsyncFifoSnsPublisher;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import software.amazon.awssdk.http.async.SdkAsyncHttpClient;
import software.amazon.awssdk.http.crt.AwsCrtAsyncHttpClient;
import software.amazon.awssdk.services.sns.SnsAsyncClient;

import java.time.Duration;

/**
 * Spring Boot auto-configuration for the async FIFO SNS publisher.
 *
 * <p>This configuration is automatically enabled when the {@code sns.publisher.topic-arn}
 * property is set in your application configuration.</p>
 *
 * <p><b>Configuration Example (application.yml):</b></p>
 * <pre>{@code
 * sns:
 *   publisher:
 *     topic-arn: arn:aws:sns:us-east-1:123456789012:MyTopic.fifo
 *     region: us-east-1        # Optional, uses default provider chain if not set
 *     partition-count: 256     # Optional, default: 256
 *     batch-timeout: 10ms      # Optional, default: 10ms
 * }</pre>
 *
 * <p><b>Bean Customization:</b> All beans created by this configuration use
 * {@code @ConditionalOnMissingBean}, allowing you to provide your own implementations
 * by defining beans of the same type in your application configuration.</p>
 *
 * <p><b>AWS Credentials:</b> The SNS client uses the default AWS credential provider chain.
 * Configure credentials via environment variables, system properties, or IAM roles.</p>
 *
 * <p><b>HTTP Client:</b> Uses AWS Common Runtime (CRT) HTTP client for optimal performance
 * and reduced memory usage in production environments.</p>
 *
 * @see SnsPublisherProperties
 * @see AsyncFifoSnsPublisher
 */
@AutoConfiguration
@EnableConfigurationProperties(SnsPublisherProperties.class)
@ConditionalOnProperty(prefix = "sns.publisher", name = "topic-arn")
public class SnsPublisherAutoConfiguration {

    private final SnsPublisherProperties properties;

    /**
     * Creates the auto-configuration with the given properties.
     *
     * @param properties the SNS publisher configuration properties
     */
    public SnsPublisherAutoConfiguration(SnsPublisherProperties properties) {
        this.properties = properties;
    }

    /**
     * Creates an AWS CRT-based async HTTP client optimized for high-throughput SNS publishing.
     *
     * <p>The AWS CRT (Common Runtime) HTTP client provides better performance and lower
     * memory usage compared to Netty, and is AWS's recommended HTTP client for production
     * workloads.</p>
     *
     * @return the configured async HTTP client
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public SdkAsyncHttpClient awsCrtHttpClient() {
        return AwsCrtAsyncHttpClient.builder()
                .maxConcurrency(Math.max(500, properties.getPartitionCount() * 2))
                .connectionTimeout(Duration.ofSeconds(10))
                .connectionMaxIdleTime(Duration.ofSeconds(60))
                .build();
    }

    /**
     * Creates the AWS SNS async client.
     *
     * <p>If a region is specified in properties, it will be used. Otherwise, the client
     * uses the default AWS region provider chain (environment, system properties, profile).</p>
     *
     * @param httpClient the async HTTP client to use for API calls
     * @return the configured SNS async client
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public SnsAsyncClient snsAsyncClient(SdkAsyncHttpClient httpClient) {
        software.amazon.awssdk.services.sns.SnsAsyncClientBuilder builder = SnsAsyncClient.builder()
                .httpClient(httpClient);

        if (properties.getRegion() != null && !properties.getRegion().isEmpty()) {
            builder.region(software.amazon.awssdk.regions.Region.of(properties.getRegion()));
        }

        return builder.build();
    }

    /**
     * Creates the async FIFO SNS publisher bean.
     *
     * <p>The publisher is configured with the topic ARN, partition count, and batch timeout
     * from the application properties.</p>
     *
     * @param snsClient the SNS async client to use for publishing
     * @return the configured publisher instance
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(SnsAsyncClient.class)
    public AsyncFifoSnsPublisher asyncFifoSnsPublisher(SnsAsyncClient snsClient) {
        return new AsyncFifoSnsPublisher(
                snsClient,
                properties.getTopicArn(),
                properties.getPartitionCount(),
                properties.getBatchTimeout());
    }
}
