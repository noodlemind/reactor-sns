package com.example.snspublisher;

import com.example.snspublisher.config.SnsPublisherAutoConfiguration;
import com.example.snspublisher.config.SnsPublisherProperties;
import com.example.snspublisher.service.AsyncFifoSnsPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.sns.SnsAsyncClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class LibrarySmokeTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SnsPublisherAutoConfiguration.class))
            .withPropertyValues("sns.publisher.region=us-east-1");

    @Test
    void testAutoConfigurationWithoutProperties() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(AsyncFifoSnsPublisher.class);
        });
    }

    @Test
    void testAutoConfigurationWithProperties() {
        contextRunner
                .withPropertyValues("sns.publisher.topic-arn=arn:aws:sns:us-east-1:123456789012:MyTopic.fifo")
                .run(context -> {
                    assertThat(context).hasSingleBean(AsyncFifoSnsPublisher.class);
                    assertThat(context).hasSingleBean(SnsAsyncClient.class);

                    SnsPublisherProperties properties = context.getBean(SnsPublisherProperties.class);
                    assertThat(properties.getTopicArn()).isEqualTo("arn:aws:sns:us-east-1:123456789012:MyTopic.fifo");
                    assertThat(properties.getPartitionCount()).isEqualTo(256); // Default
                });
    }

    @Test
    void testCustomProperties() {
        contextRunner
                .withPropertyValues(
                        "sns.publisher.topic-arn=arn:aws:sns:us-east-1:123456789012:MyTopic.fifo",
                        "sns.publisher.partition-count=10",
                        "sns.publisher.batch-timeout=50ms")
                .run(context -> {
                    assertThat(context).hasSingleBean(AsyncFifoSnsPublisher.class);

                    SnsPublisherProperties properties = context.getBean(SnsPublisherProperties.class);
                    assertThat(properties.getPartitionCount()).isEqualTo(10);
                    assertThat(properties.getBatchTimeout()).isEqualTo(Duration.ofMillis(50));
                });
    }
}
