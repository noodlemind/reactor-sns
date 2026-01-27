package io.clype.reactorsns.service;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.net.ssl.SSLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

import io.clype.reactorsns.metrics.SnsPublisherMetrics;
import io.clype.reactorsns.model.FailedEntry;
import io.clype.reactorsns.model.FifoOrderingViolationException;
import io.clype.reactorsns.model.PartialBatchFailureException;
import io.clype.reactorsns.model.SnsEvent;

import reactor.core.publisher.BufferOverflowStrategy;
import reactor.core.publisher.Flux;
import reactor.core.publisher.GroupedFlux;
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

    // ==========================================================================
    // Constants - AWS SNS Limits
    // ==========================================================================

    /** SNS FIFO maximum messages per PublishBatch API call (AWS hard limit). */
    public static final int MAX_BATCH_SIZE = 10;

    /** SNS FIFO maximum payload size per batch: 256 KB (AWS hard limit). */
    public static final int MAX_PAYLOAD_SIZE_BYTES = 256 * 1024;

    // ==========================================================================
    // Constants - Validation Patterns
    // ==========================================================================

    /** Pattern for validating SNS FIFO topic ARNs (supports all AWS partitions). */
    private static final Pattern SNS_FIFO_ARN_PATTERN = Pattern.compile(
            "^arn:aws(-[a-z-]+)?:sns:[a-z0-9-]+:\\d{12}:[a-zA-Z0-9._-]+\\.fifo$");

    /** Pattern for sanitizing log output - removes all control characters. */
    private static final Pattern LOG_SANITIZE_PATTERN = Pattern.compile("[\\p{Cntrl}\\p{Cc}]");

    // ==========================================================================
    // Constants - Retry Configuration
    // ==========================================================================

    /** Maximum number of retry attempts for transient failures. */
    private static final int MAX_RETRIES = 3;

    /** Minimum backoff duration between retries. */
    private static final Duration RETRY_MIN_BACKOFF = Duration.ofMillis(100);

    /** Maximum backoff duration between retries. */
    private static final Duration RETRY_MAX_BACKOFF = Duration.ofSeconds(5);

    /** Jitter factor for retry backoff (0.5 = 50% randomization). */
    private static final double RETRY_JITTER = 0.5;

    /** AWS error codes that indicate transient failures eligible for retry. */
    private static final Set<String> RETRYABLE_ERROR_CODES = Set.of(
            "Throttling", "InternalError", "ServiceUnavailable"
    );

    // ==========================================================================
    // Constants - Bounded Group Processing
    // ==========================================================================

    /**
     * Warning threshold for message groups per window.
     * When exceeded, a warning is logged but processing continues (no data loss).
     * Memory is already bounded by partitionBufferSize.
     */
    private static final int HIGH_GROUP_COUNT_WARNING_THRESHOLD = 1000;

    // ==========================================================================
    // Fields
    // ==========================================================================

    private final SnsAsyncClient snsClient;
    private final String topicArn;
    private final int partitionCount;
    private final Duration batchTimeout;
    private final int bufferSize;
    private final int partitionBufferSize;
    private final SnsPublisherMetrics metrics;
    private final Scheduler ioScheduler;

    // ==========================================================================
    // Constructors
    // ==========================================================================

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
    public AsyncFifoSnsPublisher(SnsAsyncClient snsClient, String topicArn,
                                  int partitionCount, Duration batchTimeout) {
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
     * @throws IllegalArgumentException if any numeric parameter is not positive
     * @throws IllegalArgumentException if topicArn does not match SNS FIFO ARN format
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
        this.ioScheduler = Schedulers.newBoundedElastic(threadPoolSize, queueCap, "sns-publisher-io");
    }

    // ==========================================================================
    // Public API
    // ==========================================================================

    /**
     * Publishes a stream of events to the SNS FIFO topic.
     *
     * <p>Events are automatically batched (up to 10 per batch) and published in parallel
     * across partitions while maintaining FIFO ordering per {@code messageGroupId}.</p>
     *
     * <p><b>Memory Bounds:</b> This implementation uses bounded memory regardless of
     * the number of unique messageGroupIds. Events are partitioned by hash, and within
     * each partition, a bounded number of groups can be active concurrently.</p>
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
     * @param eventStream the stream of events to publish (must not be null)
     * @return a Flux emitting {@link PublishBatchResponse} for each successfully
     *         published batch; errors are signaled through the Flux's error channel
     * @throws NullPointerException if eventStream is null
     */
    public Flux<PublishBatchResponse> publishEvents(Flux<SnsEvent> eventStream) {
        return eventStream
                .onBackpressureBuffer(bufferSize, BufferOverflowStrategy.ERROR)
                .groupBy(this::computePartitionId)
                .flatMap(this::processPartition, partitionCount, 1);
    }

    /**
     * Computes the partition ID for an event based on its messageGroupId hash.
     * Events with the same messageGroupId always map to the same partition.
     */
    private int computePartitionId(SnsEvent event) {
        // Use Math.floorMod to handle negative hash codes correctly
        return Math.floorMod(event.messageGroupId().hashCode(), partitionCount);
    }

    // ==========================================================================
    // Reactive Pipeline Stages (in execution order)
    // ==========================================================================

    /**
     * Processes all events for a single partition using bounded memory.
     *
     * <p>Uses window-based processing: events are collected into time/size-bounded windows,
     * then within each window, events are grouped by messageGroupId and processed.</p>
     *
     * <p><b>Memory bound:</b> partitionCount × windowSize (partitionBufferSize)</p>
     *
     * <p><b>FIFO guarantee:</b> Windows are processed sequentially, so events for the same
     * messageGroupId in window N complete before events in window N+1 start.</p>
     *
     * <p><b>Parallelism:</b> Different messageGroupIds within the same window are processed
     * in parallel, providing high throughput.</p>
     */
    private Flux<PublishBatchResponse> processPartition(GroupedFlux<Integer, SnsEvent> partitionFlux) {
        return partitionFlux
                .publishOn(ioScheduler)
                .bufferTimeout(partitionBufferSize, batchTimeout)
                .concatMap(this::processWindowedEvents);  // Windows sequential = FIFO across windows
    }

    /**
     * Processes a window of events, grouping by messageGroupId and processing groups in parallel.
     *
     * <p>Uses {@code flatMapDelayError} to allow all groups to complete before propagating
     * any errors. This ensures that if Group A fails, Groups B and C can still complete
     * their work (independent processing across groups).</p>
     */
    private Flux<PublishBatchResponse> processWindowedEvents(List<SnsEvent> events) {
        if (events.isEmpty()) {
            return Flux.empty();
        }

        // Group events by messageGroupId within this window
        // Pre-size HashMap to reduce rehashing overhead
        Map<String, List<SnsEvent>> byGroup = events.stream()
                .collect(Collectors.groupingBy(
                        SnsEvent::messageGroupId,
                        () -> new java.util.HashMap<>(Math.min(events.size(), HIGH_GROUP_COUNT_WARNING_THRESHOLD)),
                        Collectors.toList()));

        // Log warning for high group count but continue processing (no data loss)
        // Memory is already bounded by partitionBufferSize
        if (byGroup.size() > HIGH_GROUP_COUNT_WARNING_THRESHOLD) {
            log.warn("High message group count in window: {} groups (threshold: {}). " +
                    "Consider increasing partitionCount for better distribution.",
                    byGroup.size(), HIGH_GROUP_COUNT_WARNING_THRESHOLD);
        }

        // Process groups in parallel, batches within each group sequentially
        // Use flatMapDelayError to allow all groups to complete before propagating errors
        // This ensures one failing group doesn't cancel other independent groups
        return Flux.fromIterable(byGroup.values())
                .flatMapDelayError(this::processGroupBatches, byGroup.size(), 1);
    }

    /**
     * Processes all events for a single messageGroupId within a window.
     * Events are split into MAX_BATCH_SIZE batches and processed sequentially (FIFO).
     */
    private Flux<PublishBatchResponse> processGroupBatches(List<SnsEvent> groupEvents) {
        // Split into batches of MAX_BATCH_SIZE
        List<List<SnsEvent>> batches = partitionList(groupEvents, MAX_BATCH_SIZE);

        return Flux.fromIterable(batches)
                .flatMapIterable(this::splitByPayloadSize, 1)
                .concatMap(this::publishBatchWithMetrics);  // Sequential within group = FIFO
    }

    /**
     * Partitions a list into sublists of at most the specified size.
     */
    private <T> List<List<T>> partitionList(List<T> list, int size) {
        List<List<T>> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            result.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return result;
    }

    /**
     * Splits a batch into smaller sub-batches if the total payload exceeds 256KB.
     *
     * <p>All events in the input batch are guaranteed to have the same messageGroupId
     * (enforced by upstream {@code groupBy(SnsEvent::messageGroupId)}). This method
     * only needs to split by payload size to comply with SNS limits.</p>
     */
    private List<List<SnsEvent>> splitByPayloadSize(List<SnsEvent> batch) {
        List<List<SnsEvent>> result = new ArrayList<>();
        List<SnsEvent> currentSubBatch = new ArrayList<>();
        int currentSize = 0;

        for (SnsEvent event : batch) {
            int eventSize = estimateUtf8Size(event.payload());

            // Seal current sub-batch if adding this event would exceed limit
            // (but allow single oversized events to pass through for downstream handling)
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

    /**
     * Wraps batch publishing with metrics collection (if metrics are enabled).
     */
    private Mono<PublishBatchResponse> publishBatchWithMetrics(List<SnsEvent> batch) {
        if (metrics == null) {
            return publishBatch(batch);
        }

        final long startTime = System.nanoTime();
        final int batchSize = batch.size();
        metrics.incrementActiveRequests();

        return publishBatch(batch)
                .doOnSuccess(response -> recordSuccessMetrics(response, startTime))
                .doOnError(e -> metrics.recordBatchFailure(batchSize))
                .doFinally(signal -> metrics.decrementActiveRequests());
    }

    private void recordSuccessMetrics(PublishBatchResponse response, long startTime) {
        long latencyNanos = System.nanoTime() - startTime;
        int successCount = response.successful() != null ? response.successful().size() : 0;
        int failCount = response.failed() != null ? response.failed().size() : 0;
        metrics.recordBatchSuccess(successCount, latencyNanos);
        if (failCount > 0) {
            metrics.recordPartialFailure(failCount);
        }
    }

    // ==========================================================================
    // Batch Publishing
    // ==========================================================================

    /**
     * Publishes a batch of events to SNS with retry logic for transient failures.
     *
     * <p>Note: Retry is placed AFTER flatMap so PartialBatchFailureException is retried.
     * On retry, the entire batch is re-sent. SNS FIFO deduplication (5-minute window)
     * ensures already-successful messages are not duplicated.</p>
     */
    private Mono<PublishBatchResponse> publishBatch(List<SnsEvent> batch) {
        if (batch.isEmpty()) {
            return Mono.empty();
        }

        PublishBatchRequest request = buildBatchRequest(batch);

        return Mono.fromFuture(() -> snsClient.publishBatch(request))
                .flatMap(response -> handleBatchResponse(response, batch))
                .retryWhen(createRetrySpec())
                .doOnError(e -> log.error("Failed to publish batch after retries: {}",
                        sanitizeForLog(e.getMessage())));
    }

    /**
     * Builds the SNS PublishBatchRequest from a list of events.
     */
    private PublishBatchRequest buildBatchRequest(List<SnsEvent> batch) {
        List<PublishBatchRequestEntry> entries = new ArrayList<>(batch.size());

        for (int i = 0; i < batch.size(); i++) {
            SnsEvent event = batch.get(i);
            entries.add(PublishBatchRequestEntry.builder()
                    .id(event.messageDeduplicationId() + "-" + i)
                    .messageGroupId(event.messageGroupId())
                    .message(event.payload())
                    .messageDeduplicationId(event.messageDeduplicationId())
                    .build());
        }

        return PublishBatchRequest.builder()
                .topicArn(topicArn)
                .publishBatchRequestEntries(entries)
                .build();
    }

    /**
     * Handles the SNS batch response, converting partial failures to errors.
     *
     * <p>Detects FIFO ordering violations: if any successful message has a higher
     * position than any failed message in the batch, a non-retryable
     * {@link FifoOrderingViolationException} is thrown because the later message
     * was already delivered before the earlier one.</p>
     */
    private Mono<PublishBatchResponse> handleBatchResponse(PublishBatchResponse response, List<SnsEvent> batch) {
        int batchSize = batch.size();

        if (response.failed() == null || response.failed().isEmpty()) {
            log.debug("Successfully published batch of {} events.", batchSize);
            return Mono.just(response);
        }

        int successCount = response.successful() != null ? response.successful().size() : 0;
        log.error("Batch had {} failures out of {} messages. Failed IDs: {}",
                response.failed().size(),
                batchSize,
                sanitizeForLog(response.failed().stream()
                        .map(e -> e.id())
                        .collect(Collectors.joining(", "))));

        List<FailedEntry> failedEntries = response.failed().stream()
                .map(e -> new FailedEntry(e.id(), e.code(), e.message(), e.senderFault()))
                .toList();

        // Detect FIFO ordering violations
        if (successCount > 0 && hasFifoViolation(response, batchSize)) {
            String messageGroupId = batch.get(0).messageGroupId();
            log.error("FIFO ordering violation detected for messageGroupId '{}': " +
                    "a message was delivered after a failed message in the same batch. " +
                    "Cannot retry without causing out-of-order delivery.",
                    sanitizeForLog(messageGroupId));
            return Mono.error(new FifoOrderingViolationException(messageGroupId, failedEntries, successCount));
        }

        return Mono.error(new PartialBatchFailureException(failedEntries, successCount));
    }

    /**
     * Checks if a partial batch failure resulted in a FIFO ordering violation.
     *
     * <p>A violation occurs when a message at a later position in the batch succeeded
     * while a message at an earlier position failed. Since SNS already delivered the
     * later message, retrying would cause out-of-order delivery.</p>
     *
     * <p><b>Fail-safe behavior:</b> If position parsing fails for any entry, this method
     * assumes a FIFO violation occurred (returns true). This prevents potentially unsafe
     * retries when we cannot reliably determine ordering.</p>
     *
     * @return true if a FIFO violation is detected or cannot be ruled out
     */
    private boolean hasFifoViolation(PublishBatchResponse response, int batchSize) {
        // Extract positions from entry IDs (format: "dedup-id-{position}")
        // Use OptionalInt to detect parsing failures
        int minFailedPosition = Integer.MAX_VALUE;
        boolean failedParsingError = false;

        for (var entry : response.failed()) {
            int pos = extractPositionFromId(entry.id());
            if (pos < 0) {
                failedParsingError = true;
                log.warn("Failed to parse position from entry ID '{}', assuming FIFO violation for safety",
                        sanitizeForLog(entry.id()));
            } else {
                minFailedPosition = Math.min(minFailedPosition, pos);
            }
        }

        int maxSuccessPosition = -1;
        boolean successParsingError = false;

        for (var entry : response.successful()) {
            int pos = extractPositionFromId(entry.id());
            if (pos < 0) {
                successParsingError = true;
                log.warn("Failed to parse position from entry ID '{}', assuming FIFO violation for safety",
                        sanitizeForLog(entry.id()));
            } else {
                maxSuccessPosition = Math.max(maxSuccessPosition, pos);
            }
        }

        // Fail-safe: if we couldn't parse any positions, assume FIFO violation
        if (failedParsingError || successParsingError) {
            return true;
        }

        // No failures parsed = no FIFO violation possible
        if (minFailedPosition == Integer.MAX_VALUE) {
            return false;
        }

        // FIFO violation: a message after a failed position was delivered
        return maxSuccessPosition > minFailedPosition;
    }

    /**
     * Extracts the batch position from an entry ID.
     * Entry IDs are formatted as "{dedup-id}-{position}" where position is appended by us.
     *
     * @param id the entry ID
     * @return the position (0-based), or -1 if parsing fails
     */
    private int extractPositionFromId(String id) {
        if (id == null || id.isEmpty()) {
            return -1;
        }

        int lastDash = id.lastIndexOf('-');
        if (lastDash >= 0 && lastDash < id.length() - 1) {
            try {
                return Integer.parseInt(id.substring(lastDash + 1));
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }

    /**
     * Creates the retry specification for transient failure handling.
     */
    private Retry createRetrySpec() {
        return Retry.backoff(MAX_RETRIES, RETRY_MIN_BACKOFF)
                .maxBackoff(RETRY_MAX_BACKOFF)
                .jitter(RETRY_JITTER)
                .filter(this::isRetryableException)
                .doBeforeRetry(signal ->
                        log.warn("Retrying batch publication (attempt {}): {}",
                                signal.totalRetries() + 1,
                                sanitizeForLog(signal.failure().getMessage())));
    }

    // ==========================================================================
    // Error Handling
    // ==========================================================================

    /**
     * Determines if an exception is retryable (transient error).
     */
    private boolean isRetryableException(Throwable throwable) {
        // Handle partial batch failures - retry only if ALL failures are service-side (not sender faults)
        if (throwable instanceof PartialBatchFailureException partialEx) {
            return partialEx.getFailedEntries().stream().noneMatch(FailedEntry::senderFault);
        }

        if (throwable instanceof SnsException snsEx) {
            return isRetryableSnsError(snsEx);
        }
        return isNetworkException(throwable) ||
                (throwable.getCause() != null && isNetworkException(throwable.getCause()));
    }

    /**
     * Checks if an SNS exception indicates a retryable error.
     */
    private boolean isRetryableSnsError(SnsException snsEx) {
        if (snsEx.awsErrorDetails() == null) {
            return false;
        }
        return RETRYABLE_ERROR_CODES.contains(snsEx.awsErrorDetails().errorCode());
    }

    /**
     * Checks if the exception is a network-related transient failure.
     */
    private boolean isNetworkException(Throwable t) {
        return t instanceof SocketTimeoutException ||
                t instanceof IOException ||
                t instanceof SSLException;
    }

    // ==========================================================================
    // Utilities
    // ==========================================================================

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

    // ==========================================================================
    // Lifecycle
    // ==========================================================================

    /**
     * Disposes of the internal scheduler when the Spring context is destroyed.
     * This method is called automatically by Spring's lifecycle management.
     */
    @Override
    public void destroy() {
        ioScheduler.dispose();
    }
}
