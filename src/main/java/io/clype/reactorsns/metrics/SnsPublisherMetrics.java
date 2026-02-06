package io.clype.reactorsns.metrics;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;

/**
 * Collects and exposes metrics for SNS publishing operations.
 *
 * <p>This class provides comprehensive observability into the SNS publisher's behavior,
 * including throughput, latency, and error tracking.</p>
 *
 * <p><b>Available Metrics:</b></p>
 * <ul>
 *   <li>{@code sns.publisher.batches.completed} - Counter of successful batch publishes</li>
 *   <li>{@code sns.publisher.batches.failed} - Counter of failed batch publishes</li>
 *   <li>{@code sns.publisher.events.published} - Counter of successfully published events</li>
 *   <li>{@code sns.publisher.events.failed} - Counter of events that failed to publish</li>
 *   <li>{@code sns.publisher.publish.latency} - Timer measuring publish latency (p50, p95, p99)</li>
 *   <li>{@code sns.publisher.requests.active} - Gauge of in-flight publish requests</li>
 *   <li>{@code sns.publisher.batch.size} - Summary of events per batch</li>
 * </ul>
 *
 * <p>All metrics are tagged with the SNS topic name for multi-topic environments.</p>
 */
public class SnsPublisherMetrics {

    private static final String METRIC_PREFIX = "sns.publisher";
    private static final double[] PUBLISH_LATENCY_PERCENTILES = {0.5, 0.95, 0.99};

    private final Counter batchesCompleted;
    private final Counter batchesFailed;
    private final Counter eventsPublished;
    private final Counter eventsFailed;
    private final Timer publishLatency;
    private final AtomicInteger activeRequests;
    private final DistributionSummary batchSize;

    /**
     * Creates a new SnsPublisherMetrics instance.
     *
     * @param registry the Micrometer registry to register metrics with
     * @param topicArn the SNS topic ARN (used for tagging metrics)
     */
    public SnsPublisherMetrics(MeterRegistry registry, String topicArn) {
        String topicName = extractTopicName(topicArn);
        Tags tags = Tags.of("topic", topicName);

        this.batchesCompleted = Counter.builder(METRIC_PREFIX + ".batches.completed")
                .description("Number of batches successfully published to SNS")
                .tags(tags)
                .register(registry);

        this.batchesFailed = Counter.builder(METRIC_PREFIX + ".batches.failed")
                .description("Number of batches that failed to publish to SNS")
                .tags(tags)
                .register(registry);

        this.eventsPublished = Counter.builder(METRIC_PREFIX + ".events.published")
                .description("Total number of events successfully published to SNS")
                .tags(tags)
                .register(registry);

        this.eventsFailed = Counter.builder(METRIC_PREFIX + ".events.failed")
                .description("Total number of events that failed to publish to SNS")
                .tags(tags)
                .register(registry);

        this.publishLatency = Timer.builder(METRIC_PREFIX + ".publish.latency")
                .description("Time taken to publish a batch to SNS")
                .tags(tags)
                .publishPercentiles(PUBLISH_LATENCY_PERCENTILES)
                .register(registry);

        this.activeRequests = new AtomicInteger(0);
        Gauge.builder(METRIC_PREFIX + ".requests.active", activeRequests, AtomicInteger::get)
                .description("Number of in-flight publish requests to SNS")
                .tags(tags)
                .register(registry);

        this.batchSize = DistributionSummary.builder(METRIC_PREFIX + ".batch.size")
                .description("Number of events per batch")
                .tags(tags)
                .register(registry);
    }

    /**
     * Records a successful batch publication.
     *
     * @param eventCount   number of events in the batch
     * @param latencyNanos time taken to publish in nanoseconds
     */
    public void recordBatchSuccess(int eventCount, long latencyNanos) {
        batchesCompleted.increment();
        eventsPublished.increment(eventCount);
        publishLatency.record(latencyNanos, TimeUnit.NANOSECONDS);
        batchSize.record(eventCount);
    }

    /**
     * Records a partial batch failure (some events in batch failed).
     *
     * @param failedCount number of events that failed in the batch
     */
    public void recordPartialFailure(int failedCount) {
        eventsFailed.increment(failedCount);
    }

    /**
     * Records a complete batch failure.
     *
     * @param eventCount number of events in the failed batch
     */
    public void recordBatchFailure(int eventCount) {
        batchesFailed.increment();
        eventsFailed.increment(eventCount);
    }

    /**
     * Increments the active requests counter.
     * Call this when starting a publish operation.
     */
    public void incrementActiveRequests() {
        activeRequests.incrementAndGet();
    }

    /**
     * Decrements the active requests counter.
     * Call this when a publish operation completes (success or failure).
     */
    public void decrementActiveRequests() {
        activeRequests.decrementAndGet();
    }

    /**
     * Extracts the topic name from an ARN.
     *
     * @param topicArn the full ARN (e.g., arn:aws:sns:us-east-1:123456789012:MyTopic.fifo)
     * @return the topic name (e.g., MyTopic.fifo)
     */
    private static String extractTopicName(String topicArn) {
        if (topicArn == null || topicArn.isEmpty()) {
            return "unknown";
        }
        int lastColon = topicArn.lastIndexOf(':');
        if (lastColon >= 0 && lastColon < topicArn.length() - 1) {
            return topicArn.substring(lastColon + 1);
        }
        return topicArn;
    }
}
