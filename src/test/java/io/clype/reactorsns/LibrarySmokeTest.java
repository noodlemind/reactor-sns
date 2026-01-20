package io.clype.reactorsns;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.clype.reactorsns.config.SnsPublisherAutoConfiguration;
import io.clype.reactorsns.config.SnsPublisherProperties;
import io.clype.reactorsns.service.AsyncFifoSnsPublisher;

import software.amazon.awssdk.services.sns.SnsAsyncClient;

import static org.assertj.core.api.Assertions.assertThat;

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
