package com.example.snspublisher.service;

import com.example.snspublisher.model.SnsEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import software.amazon.awssdk.services.sns.SnsAsyncClient;
import software.amazon.awssdk.services.sns.model.PublishBatchRequest;
import software.amazon.awssdk.services.sns.model.PublishBatchRequestEntry;
import software.amazon.awssdk.services.sns.model.PublishBatchResponse;
import software.amazon.awssdk.services.sns.model.SnsException;
import reactor.core.publisher.BufferOverflowStrategy;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import reactor.core.scheduler.Schedulers;
import reactor.core.scheduler.Scheduler;

/**
 * High-throughput asynchronous publisher for AWS SNS FIFO topics.
 *
 * <p>This publisher provides the following guarantees and features:</p>
 * <ul>
 *   <li><b>FIFO Ordering:</b> Messages with the same {@code messageGroupId} are delivered
 *       in the order they were published.</li>
 *   <li><b>High Throughput:</b> Uses parallel processing across configurable partitions
 *       while maintaining per-group ordering.</li>
 *   <li><b>Batching:</b> Automatically batches messages (up to 10 per batch, SNS limit)
 *       to reduce API calls and improve efficiency.</li>
 *   <li><b>Backpressure:</b> Built-in backpressure handling prevents memory exhaustion
 *       under high load.</li>
 *   <li><b>Retry Logic:</b> Automatic retry with exponential backoff for transient failures
 *       (throttling, network issues).</li>
 * </ul>
 *
 * <p><b>Usage Example:</b></p>
 * <pre>{@code
 * @Autowired
 * private AsyncFifoSnsPublisher publisher;
 *
 * public void publishMessages() {
 *     Flux<SnsEvent> events = Flux.just(
 *         new SnsEvent("order-123", "event-1", "{\"action\":\"created\"}"),
 *         new SnsEvent("order-123", "event-2", "{\"action\":\"shipped\"}")
 *     );
 *
 *     publisher.publishEvents(events)
 *         .subscribe(
 *             response -> log.info("Batch published"),
 *             error -> log.error("Failed to publish", error)
 *         );
 * }
 * }</pre>
 *
 * <p><b>Thread Safety:</b> This class is thread-safe. Multiple threads can call
 * {@link #publishEvents(Flux)} concurrently.</p>
 *
 * <p><b>Resource Management:</b> This class implements {@link DisposableBean} and will
 * automatically clean up its thread pool when the Spring context is destroyed.</p>
 *
 * @see SnsEvent
 * @see com.example.snspublisher.config.SnsPublisherAutoConfiguration
 */
public class AsyncFifoSnsPublisher implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(AsyncFifoSnsPublisher.class);

    /** SNS FIFO topic maximum batch size limit. */
    private static final int MAX_BATCH_SIZE = 10;

    /** SNS maximum payload size per batch (256KB). */
    private static final int MAX_PAYLOAD_SIZE_BYTES = 256 * 1024;

    private final int partitionCount;
    private final Duration batchTimeout;
    private final SnsAsyncClient snsClient;
    private final String topicArn;
    private final Scheduler ioScheduler;

    /**
     * Creates a new AsyncFifoSnsPublisher.
     *
     * @param snsClient      the AWS SNS async client to use for publishing
     * @param topicArn       the ARN of the SNS FIFO topic (must end with .fifo)
     * @param partitionCount number of logical partitions for parallel processing (default: 256)
     * @param batchTimeout   maximum time to wait for a batch to fill before sending (default: 10ms)
     * @throws NullPointerException if snsClient, topicArn, or batchTimeout is null
     * @throws IllegalArgumentException if partitionCount is not positive
     */
    public AsyncFifoSnsPublisher(SnsAsyncClient snsClient, String topicArn, int partitionCount, Duration batchTimeout) {
        this.snsClient = java.util.Objects.requireNonNull(snsClient, "snsClient cannot be null");
        this.topicArn = java.util.Objects.requireNonNull(topicArn, "topicArn cannot be null");
        this.batchTimeout = java.util.Objects.requireNonNull(batchTimeout, "batchTimeout cannot be null");
        if (partitionCount <= 0) {
            throw new IllegalArgumentException("partitionCount must be positive");
        }
        this.partitionCount = partitionCount;
        // Bounded elastic scheduler is ideal for I/O intensive tasks
        this.ioScheduler = Schedulers.newBoundedElastic(
                partitionCount + 50,
                partitionCount * 10,
                "sns-publisher-io");
    }

    /**
     * Publishes a stream of events to the SNS FIFO topic.
     *
     * <p>Events are automatically batched (up to 10 per batch) and published in parallel
     * across partitions while maintaining FIFO ordering per {@code messageGroupId}.</p>
     *
     * <p><b>Ordering Guarantee:</b> Events with the same {@code messageGroupId} will be
     * delivered in the order they appear in the input stream. Events with different
     * {@code messageGroupId} values may be processed concurrently.</p>
     *
     * <p><b>Error Handling:</b></p>
     * <ul>
     *   <li>Transient errors (throttling, network issues) are automatically retried
     *       up to 3 times with exponential backoff.</li>
     *   <li>Partial batch failures (some messages in a batch fail) will cause the
     *       returned Flux to emit an error.</li>
     *   <li>Non-retryable errors (validation, authorization) fail immediately.</li>
     * </ul>
     *
     * <p><b>Backpressure:</b> If the publisher cannot keep up with the input stream,
     * older events will be dropped and logged. Configure {@code partitionCount} and
     * {@code batchTimeout} to tune throughput.</p>
     *
     * @param eventStream the stream of events to publish (must not be null)
     * @return a Flux emitting {@link PublishBatchResponse} for each successfully
     *         published batch; errors are signaled through the Flux's error channel
     * @throws NullPointerException if eventStream is null
     */
    public Flux<PublishBatchResponse> publishEvents(Flux<SnsEvent> eventStream) {
        return eventStream
                .onBackpressureBuffer(100000,
                    dropped -> log.warn("Dropped event due to backpressure: {}", dropped.messageGroupId()),
                    BufferOverflowStrategy.DROP_OLDEST)
                .groupBy(event -> (event.messageGroupId().hashCode() & Integer.MAX_VALUE) % partitionCount)
                .flatMap(partitionFlux -> partitionFlux
                        .publishOn(ioScheduler)
                        .transform(this::bufferByBatchSizeAndPayload)
                        .onBackpressureBuffer(1000)
                        .concatMap(this::publishBatch), partitionCount);
    }

    private Flux<List<SnsEvent>> bufferByBatchSizeAndPayload(Flux<SnsEvent> input) {
        return input.bufferTimeout(MAX_BATCH_SIZE, batchTimeout)
                // After standard buffering, we must split batches that exceed payload size
                // This is a safety valve. It's more efficient to check size during
                // accumulation,
                // but bufferTimeout is highly optimized for time.
                .flatMapIterable(this::splitBatchByPayloadSize);
    }

    private List<List<SnsEvent>> splitBatchByPayloadSize(List<SnsEvent> batch) {
        List<List<SnsEvent>> result = new ArrayList<>();
        List<SnsEvent> currentSubBatch = new ArrayList<>();
        int currentSize = 0;

        for (SnsEvent event : batch) {
            int eventSize = event.payload() != null ? event.payload().getBytes(StandardCharsets.UTF_8).length : 0;

            // If adding this event exceeds limit, seal the current sub-batch
            // Also ensure we don't split if the sub-batch is empty (single large event case
            // must fail downstream or be handled otherwise)
            if (!currentSubBatch.isEmpty() && (currentSize + eventSize > MAX_PAYLOAD_SIZE_BYTES)) {
                result.add(currentSubBatch);
                currentSubBatch = new ArrayList<>();
                currentSize = 0;
            }

            currentSubBatch.add(event);
            currentSize += eventSize;
        }

        if (!currentSubBatch.isEmpty()) {
            result.add(currentSubBatch);
        }

        return result;
    }

    private Mono<PublishBatchResponse> publishBatch(List<SnsEvent> batch) {
        if (batch.isEmpty()) {
            return Mono.empty();
        }

        List<PublishBatchRequestEntry> entries = new ArrayList<>(batch.size());
        for (int i = 0; i < batch.size(); i++) {
            SnsEvent event = batch.get(i);
            entries.add(PublishBatchRequestEntry.builder()
                    .id(event.messageDeduplicationId() + "-" + i)  // Unique ID per batch entry
                    .messageGroupId(event.messageGroupId())
                    .message(event.payload())
                    .messageDeduplicationId(event.messageDeduplicationId())
                    .build());
        }

        PublishBatchRequest request = PublishBatchRequest.builder()
                .topicArn(topicArn)
                .publishBatchRequestEntries(entries)
                .build();

        return Mono.fromFuture(() -> snsClient.publishBatch(request))
                .retryWhen(Retry.backoff(3, Duration.ofMillis(100))
                        .maxBackoff(Duration.ofSeconds(5))
                        .jitter(0.5)
                        .filter(throwable -> isRetryableException(throwable))
                        .doBeforeRetry(signal ->
                            log.warn("Retrying batch publication (attempt {}): {}",
                                signal.totalRetries() + 1, signal.failure().getMessage())))
                .flatMap(response -> {
                    if (response.failed() != null && !response.failed().isEmpty()) {
                        log.error("Batch had {} failures out of {} messages. Failed IDs: {}",
                                response.failed().size(),
                                batch.size(),
                                response.failed().stream()
                                    .map(e -> e.id())
                                    .collect(java.util.stream.Collectors.joining(", ")));
                        return Mono.error(new RuntimeException(
                                "Partial batch failure: " + response.failed().size() + " messages failed"));
                    }
                    log.debug("Successfully published batch of {} events.", batch.size());
                    return Mono.just(response);
                })
                .doOnError(e -> log.error("Failed to publish batch after retries: {}", e.getMessage()));
    }

    /**
     * Determines if an exception is retryable (transient error).
     *
     * @param throwable the exception to check
     * @return true if the exception should be retried
     */
    private boolean isRetryableException(Throwable throwable) {
        // Check for AWS SNS-specific retryable errors
        if (throwable instanceof SnsException snsEx) {
            String errorCode = snsEx.awsErrorDetails() != null ?
                snsEx.awsErrorDetails().errorCode() : "";
            return "Throttling".equals(errorCode) ||
                   "InternalError".equals(errorCode) ||
                   "ServiceUnavailable".equals(errorCode);
        }

        // Check the exception and its cause for network/IO errors
        Throwable cause = throwable.getCause();
        return isNetworkException(throwable) || (cause != null && isNetworkException(cause));
    }

    /**
     * Checks if the exception is a network-related transient failure.
     */
    private boolean isNetworkException(Throwable t) {
        return t instanceof java.net.SocketTimeoutException ||
               t instanceof java.io.IOException ||
               t instanceof javax.net.ssl.SSLException ||
               t.getClass().getName().equals("io.netty.handler.timeout.ReadTimeoutException") ||
               t.getClass().getName().equals("io.netty.handler.timeout.WriteTimeoutException");
    }

    /**
     * Disposes of the internal scheduler when the Spring context is destroyed.
     * This method is called automatically by Spring's lifecycle management.
     */
    @Override
    public void destroy() {
        if (ioScheduler != null) {
            ioScheduler.dispose();
        }
    }
}
