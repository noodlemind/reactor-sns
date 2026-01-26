package io.clype.reactorsns.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class SnsPublisherMetricsTest {

    private SimpleMeterRegistry registry;
    private SnsPublisherMetrics metrics;
    private static final String TOPIC_ARN = "arn:aws:sns:us-east-1:123456789012:MyTopic.fifo";

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new SnsPublisherMetrics(registry, TOPIC_ARN);
    }

    @Test
    void shouldRecordBatchSuccess() {
        metrics.recordBatchSuccess(10, 5_000_000L); // 5ms in nanos

        assertEquals(1.0, registry.counter("sns.publisher.batches.completed", "topic", "MyTopic.fifo").count());
        assertEquals(10.0, registry.counter("sns.publisher.events.published", "topic", "MyTopic.fifo").count());
    }

    @Test
    void shouldRecordMultipleBatchSuccesses() {
        metrics.recordBatchSuccess(10, 5_000_000L);
        metrics.recordBatchSuccess(5, 3_000_000L);
        metrics.recordBatchSuccess(8, 4_000_000L);

        assertEquals(3.0, registry.counter("sns.publisher.batches.completed", "topic", "MyTopic.fifo").count());
        assertEquals(23.0, registry.counter("sns.publisher.events.published", "topic", "MyTopic.fifo").count());
    }

    @Test
    void shouldRecordBatchFailure() {
        metrics.recordBatchFailure(10);

        assertEquals(1.0, registry.counter("sns.publisher.batches.failed", "topic", "MyTopic.fifo").count());
        assertEquals(10.0, registry.counter("sns.publisher.events.failed", "topic", "MyTopic.fifo").count());
    }

    @Test
    void shouldRecordPartialFailure() {
        metrics.recordPartialFailure(3);

        assertEquals(3.0, registry.counter("sns.publisher.events.failed", "topic", "MyTopic.fifo").count());
        // Partial failure doesn't increment batch failure counter
        assertEquals(0.0, registry.counter("sns.publisher.batches.failed", "topic", "MyTopic.fifo").count());
    }

    @Test
    void shouldTrackActiveRequests() {
        assertEquals(0.0, registry.get("sns.publisher.requests.active").gauge().value());

        metrics.incrementActiveRequests();
        assertEquals(1.0, registry.get("sns.publisher.requests.active").gauge().value());

        metrics.incrementActiveRequests();
        assertEquals(2.0, registry.get("sns.publisher.requests.active").gauge().value());

        metrics.decrementActiveRequests();
        assertEquals(1.0, registry.get("sns.publisher.requests.active").gauge().value());

        metrics.decrementActiveRequests();
        assertEquals(0.0, registry.get("sns.publisher.requests.active").gauge().value());
    }

    @Test
    void shouldRecordLatency() {
        metrics.recordBatchSuccess(10, 5_000_000L); // 5ms
        metrics.recordBatchSuccess(10, 10_000_000L); // 10ms

        var timer = registry.timer("sns.publisher.publish.latency", "topic", "MyTopic.fifo");
        assertEquals(2, timer.count());
        // Total time should be 15ms = 0.015 seconds
        assertEquals(0.015, timer.totalTime(java.util.concurrent.TimeUnit.SECONDS), 0.001);
    }

    @Test
    void shouldRecordBatchSize() {
        metrics.recordBatchSuccess(10, 5_000_000L);
        metrics.recordBatchSuccess(5, 3_000_000L);
        metrics.recordBatchSuccess(8, 4_000_000L);

        var summary = registry.summary("sns.publisher.batch.size", "topic", "MyTopic.fifo");
        assertEquals(3, summary.count());
        assertEquals(23.0, summary.totalAmount());
    }

    @Test
    void shouldHandleNullTopicArn() {
        var metricsWithNullArn = new SnsPublisherMetrics(registry, null);
        metricsWithNullArn.recordBatchSuccess(5, 1_000_000L);

        // Should use "unknown" as topic name
        assertEquals(1.0, registry.counter("sns.publisher.batches.completed", "topic", "unknown").count());
    }

    @Test
    void shouldHandleEmptyTopicArn() {
        var metricsWithEmptyArn = new SnsPublisherMetrics(registry, "");
        metricsWithEmptyArn.recordBatchSuccess(5, 1_000_000L);

        // Should use "unknown" as topic name
        assertEquals(1.0, registry.counter("sns.publisher.batches.completed", "topic", "unknown").count());
    }
}
