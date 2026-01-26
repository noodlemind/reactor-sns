package io.clype.reactorsns.service;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.net.ssl.SSLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

import io.clype.reactorsns.metrics.SnsPublisherMetrics;
import io.clype.reactorsns.model.FailedEntry;
import io.clype.reactorsns.model.PartialBatchFailureException;
import io.clype.reactorsns.model.SnsEvent;

import reactor.core.publisher.BufferOverflowStrategy;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import software.amazon.awssdk.services.sns.SnsAsyncClient;
import software.amazon.awssdk.services.sns.model.PublishBatchRequest;
import software.amazon.awssdk.services.sns.model.PublishBatchRequestEntry;
import software.amazon.awssdk.services.sns.model.PublishBatchResponse;
import software.amazon.awssdk.services.sns.model.SnsException;

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
 * @see io.clype.reactorsns.config.SnsPublisherAutoConfiguration
 */
public class AsyncFifoSnsPublisher implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(AsyncFifoSnsPublisher.class);

    /** SNS FIFO maximum messages per PublishBatch API call (AWS hard limit). */
    public static final int MAX_BATCH_SIZE = 10;

    /** SNS FIFO maximum payload size per batch: 256 KB (AWS hard limit). */
    public static final int MAX_PAYLOAD_SIZE_BYTES = 256 * 1024;

    /** Pattern for validating SNS FIFO topic ARNs (supports all AWS partitions). */
    private static final Pattern SNS_FIFO_ARN_PATTERN = Pattern.compile(
            "^arn:aws(-[a-z-]+)?:sns:[a-z0-9-]+:\\d{12}:[a-zA-Z0-9._-]+\\.fifo$");

    /** Pattern for sanitizing log output - removes all control characters. */
    private static final Pattern LOG_SANITIZE_PATTERN = Pattern.compile("[\\p{Cntrl}\\p{Cc}]");

    private final int partitionCount;
    private final Duration batchTimeout;
    private final SnsAsyncClient snsClient;
    private final String topicArn;
    private final Scheduler ioScheduler;
    private final int bufferSize;
    private final int partitionBufferSize;
    private final SnsPublisherMetrics metrics;

    /**
     * Creates a new AsyncFifoSnsPublisher with default buffer sizes and no metrics.
     *
     * @param snsClient      the AWS SNS async client to use for publishing
     * @param topicArn       the ARN of the SNS FIFO topic (must end with .fifo)
     * @param partitionCount number of logical partitions for parallel processing (default: 256)
     * @param batchTimeout   maximum time to wait for a batch to fill before sending (default: 10ms)
     * @throws NullPointerException if snsClient, topicArn, or batchTimeout is null
     * @throws IllegalArgumentException if partitionCount is not positive
     */
    public AsyncFifoSnsPublisher(SnsAsyncClient snsClient, String topicArn, int partitionCount, Duration batchTimeout) {
        this(snsClient, topicArn, partitionCount, batchTimeout, 10_000, 100, null);
    }

    /**
     * Creates a new AsyncFifoSnsPublisher with full configuration options.
     *
     * @param snsClient            the AWS SNS async client to use for publishing
     * @param topicArn             the ARN of the SNS FIFO topic (must end with .fifo)
     * @param partitionCount       number of logical partitions for parallel processing
     * @param batchTimeout         maximum time to wait for a batch to fill before sending
     * @param bufferSize           main buffer size for incoming events (default: 10,000)
     * @param partitionBufferSize  buffer size per partition for batched events (default: 100)
     * @param metrics              optional metrics collector (may be null)
     * @throws NullPointerException if snsClient, topicArn, or batchTimeout is null
     * @throws IllegalArgumentException if partitionCount, bufferSize, or partitionBufferSize is not positive
     * @throws IllegalArgumentException if topicArn does not end with .fifo
     */
    public AsyncFifoSnsPublisher(
            SnsAsyncClient snsClient,
            String topicArn,
            int partitionCount,
            Duration batchTimeout,
            int bufferSize,
            int partitionBufferSize,
            SnsPublisherMetrics metrics) {
        this.snsClient = Objects.requireNonNull(snsClient, "snsClient cannot be null");
        this.topicArn = Objects.requireNonNull(topicArn, "topicArn cannot be null");
        this.batchTimeout = Objects.requireNonNull(batchTimeout, "batchTimeout cannot be null");
        if (!SNS_FIFO_ARN_PATTERN.matcher(topicArn).matches()) {
            throw new IllegalArgumentException(
                    "Invalid SNS FIFO topic ARN format. Expected: arn:aws:sns:<region>:<account-id>:<topic-name>.fifo");
        }
        if (partitionCount <= 0) {
            throw new IllegalArgumentException("partitionCount must be positive");
        }
        if (bufferSize <= 0) {
            throw new IllegalArgumentException("bufferSize must be positive");
        }
        if (partitionBufferSize <= 0) {
            throw new IllegalArgumentException("partitionBufferSize must be positive");
        }
        this.partitionCount = partitionCount;
        this.bufferSize = bufferSize;
        this.partitionBufferSize = partitionBufferSize;
        this.metrics = metrics;
        // Thread pool sized for I/O-bound work (network calls to SNS spend most time waiting)
        // Using 8x CPU cores provides good parallelism while threads wait on network I/O
        int threadPoolSize = Math.min(partitionCount, Runtime.getRuntime().availableProcessors() * 8);
        // Queue cap prevents unbounded memory growth; sized to match upstream buffers
        int queueCap = bufferSize;
        this.ioScheduler = Schedulers.newBoundedElastic(
                threadPoolSize,
                queueCap,
                "sns-publisher-io");
    }

    /**
     * Publishes a stream of events to the SNS FIFO topic.
     *
     * <p>Events are automatically batched (up to 10 per batch) and published in parallel
     * across partitions while maintaining FIFO ordering per {@code messageGroupId}.</p>
     *
     * <p><b>AWS SNS FIFO Limits (Standard Mode):</b></p>
     * <ul>
     *   <li>3,000 messages/second per topic (300 batches/second)</li>
     *   <li>300 messages/second per message group</li>
     *   <li>10 messages per batch (API limit)</li>
     *   <li>256 KB payload per batch</li>
     * </ul>
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
     * <p><b>Backpressure:</b> If the publisher cannot keep up with the input stream
     * and the internal buffer is exhausted, an error is emitted. This fail-fast behavior
     * preserves FIFO ordering guarantees by preventing silent data loss.</p>
     *
     * <p><b>Throttling:</b> AWS SDK automatically handles throttling with retries.
     * If you hit SNS rate limits consistently, consider reducing partition count
     * or implementing application-level rate limiting upstream.</p>
     *
     * @param eventStream the stream of events to publish (must not be null)
     * @return a Flux emitting {@link PublishBatchResponse} for each successfully
     *         published batch; errors are signaled through the Flux's error channel
     * @throws NullPointerException if eventStream is null
     */
    public Flux<PublishBatchResponse> publishEvents(Flux<SnsEvent> eventStream) {
        return eventStream
                .onBackpressureBuffer(bufferSize, BufferOverflowStrategy.ERROR)
                .groupBy(event -> (event.messageGroupId().hashCode() & Integer.MAX_VALUE) % partitionCount)
                .flatMap(partitionFlux -> partitionFlux
                        .publishOn(ioScheduler)
                        .transform(this::bufferByBatchSizeAndPayload)
                        .onBackpressureBuffer(partitionBufferSize)
                        .concatMap(this::publishBatchWithMetrics),
                partitionCount, 1);  // prefetch=1 for tighter backpressure
    }

    private Mono<PublishBatchResponse> publishBatchWithMetrics(List<SnsEvent> batch) {
        if (metrics == null) {
            return publishBatch(batch);
        }

        final long startTime = System.nanoTime();
        final int batchSize = batch.size();
        metrics.incrementActiveRequests();

        return publishBatch(batch)
                .doOnSuccess(response -> {
                    long latencyNanos = System.nanoTime() - startTime;
                    int successCount = response.successful() != null ? response.successful().size() : 0;
                    int failCount = response.failed() != null ? response.failed().size() : 0;
                    metrics.recordBatchSuccess(successCount, latencyNanos);
                    if (failCount > 0) {
                        metrics.recordPartialFailure(failCount);
                    }
                })
                .doOnError(e -> metrics.recordBatchFailure(batchSize))
                .doFinally(signal -> metrics.decrementActiveRequests());
    }

    private Flux<List<SnsEvent>> bufferByBatchSizeAndPayload(Flux<SnsEvent> input) {
        return input.bufferTimeout(MAX_BATCH_SIZE, batchTimeout)
                .flatMapIterable(this::splitBatchByPayloadSize, 1);  // prefetch=1
    }

    private List<List<SnsEvent>> splitBatchByPayloadSize(List<SnsEvent> batch) {
        List<List<SnsEvent>> result = new ArrayList<>();
        List<SnsEvent> currentSubBatch = new ArrayList<>();
        int currentSize = 0;

        for (SnsEvent event : batch) {
            int eventSize = estimateUtf8Size(event.payload());

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
                        .filter(this::isRetryableException)
                        .doBeforeRetry(signal ->
                            log.warn("Retrying batch publication (attempt {}): {}",
                                signal.totalRetries() + 1,
                                sanitizeForLog(signal.failure().getMessage()))))
                .flatMap(response -> {
                    if (response.failed() != null && !response.failed().isEmpty()) {
                        int successCount = response.successful() != null ? response.successful().size() : 0;
                        log.error("Batch had {} failures out of {} messages. Failed IDs: {}",
                                response.failed().size(),
                                batch.size(),
                                sanitizeForLog(response.failed().stream()
                                    .map(e -> e.id())
                                    .collect(Collectors.joining(", "))));
                        List<FailedEntry> failedEntries = response.failed().stream()
                                .map(e -> new FailedEntry(e.id(), e.code(), e.message(), e.senderFault()))
                                .toList();
                        return Mono.error(new PartialBatchFailureException(failedEntries, successCount));
                    }
                    log.debug("Successfully published batch of {} events.", batch.size());
                    return Mono.just(response);
                })
                .doOnError(e -> log.error("Failed to publish batch after retries: {}",
                        sanitizeForLog(e.getMessage())));
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
        return t instanceof SocketTimeoutException ||
               t instanceof IOException ||
               t instanceof SSLException;
    }

    /**
     * Estimates UTF-8 encoded size without allocating a byte array.
     * For ASCII strings (common in JSON payloads), this is exact.
     * For non-ASCII, this provides a safe upper bound.
     */
    private int estimateUtf8Size(String str) {
        if (str == null) {
            return 0;
        }
        int size = 0;
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (c < 0x80) {
                size += 1;  // ASCII: 1 byte
            } else if (c < 0x800) {
                size += 2;  // 2-byte UTF-8
            } else {
                size += 3;  // 3-byte UTF-8 (covers BMP, surrogate pairs handled implicitly)
            }
        }
        return size;
    }

    /**
     * Sanitizes a string for safe logging by removing all control characters.
     * Prevents log injection attacks including ANSI escape sequences.
     */
    private String sanitizeForLog(String input) {
        if (input == null) {
            return "null";
        }
        return LOG_SANITIZE_PATTERN.matcher(input).replaceAll("_");
    }

    /**
     * Disposes of the internal scheduler when the Spring context is destroyed.
     * This method is called automatically by Spring's lifecycle management.
     */
    @Override
    public void destroy() {
        ioScheduler.dispose();
    }
}
