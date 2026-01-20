package com.example.snspublisher.service;

import com.example.snspublisher.model.SnsEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sns.SnsAsyncClient;
import software.amazon.awssdk.services.sns.model.PublishBatchRequest;
import software.amazon.awssdk.services.sns.model.PublishBatchRequestEntry;
import software.amazon.awssdk.services.sns.model.PublishBatchResponse;
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

// ... imports

@Service
public class AsyncFifoSnsPublisher {

    private static final Logger log = LoggerFactory.getLogger(AsyncFifoSnsPublisher.class);
    private static final int MAX_BATCH_SIZE = 10; // SNS FIFO Limit is 10
    private static final int MAX_PAYLOAD_SIZE_BYTES = 256 * 1024; // 256KB limit
    
    // Configurable parameters
    private final int partitionCount;
    private final Duration batchTimeout;
    private final SnsAsyncClient snsClient;
    private final String topicArn;
    
    // Dedicated Scheduler for SNS I/O to avoid blocking the calling threads or the main Reactor pool
    private final Scheduler ioScheduler;

    public AsyncFifoSnsPublisher(SnsAsyncClient snsClient, String topicArn) {
        // High Throughput Defaults: 256 partitions, 10ms latency buffer
        this(snsClient, topicArn, 256, Duration.ofMillis(10));
    }

    public AsyncFifoSnsPublisher(SnsAsyncClient snsClient, String topicArn, int partitionCount, Duration batchTimeout) {
        this.snsClient = snsClient;
        this.topicArn = topicArn;
        this.partitionCount = partitionCount;
        this.batchTimeout = batchTimeout;
        // Bounded elastic scheduler is ideal for I/O intensive tasks
        this.ioScheduler = Schedulers.newBoundedElastic(500, 10000, "sns-publisher-io");
    }

    /**
     * Ingests a flux of events, groups them into fixed partitions to ensure bounded concurrency,
     * buffers them into batches, and publishes them to SNS.
     * <p>
     * Ordering is maintained per MessageGroupId because all events for a specific Group ID
     * map to the same partition, and partitions are processed sequentially using concatMap.
     */
    public Flux<PublishBatchResponse> publishEvents(Flux<SnsEvent> eventStream) {
        return eventStream
            // Backpressure: If downstream (SNS) is slower than upstream (DB), buffer up to a limit (e.g. 10k items)
            // preventing OOM. If buffer fills, we can Drop, Error, or Block (depending on business need).
            // Here we buffer, assuming standard Reactor backpressure propagation works with the upstream source.
            .onBackpressureBuffer(10000) 
            
            // Distribute events into fixed partitions based on hash of GroupId.
            .groupBy(event -> Math.abs(event.messageGroupId().hashCode()) % partitionCount)
            .flatMap(partitionFlux -> 
                partitionFlux
                    // Offload processing of each partition to our dedicated I/O scheduler
                    .publishOn(ioScheduler)
                    // Buffer into chunks of 10 or flush every 10ms (configurable)
                    .transform(this::bufferByBatchSizeAndPayload)
                    // Process each batch in this partition sequentially to ensure FIFO order
                    .concatMap(this::publishBatch)
            , partitionCount); // Max concurrency matches partition count
    }

    private Flux<List<SnsEvent>> bufferByBatchSizeAndPayload(Flux<SnsEvent> input) {
        return input.bufferTimeout(MAX_BATCH_SIZE, batchTimeout)
             // After standard buffering, we must split batches that exceed payload size
             // This is a safety valve. It's more efficient to check size during accumulation, 
             // but bufferTimeout is highly optimized for time.
             .flatMapIterable(this::splitBatchByPayloadSize);
    }
    
    private List<List<SnsEvent>> splitBatchByPayloadSize(List<SnsEvent> batch) {
        List<List<SnsEvent>> result = new ArrayList<>();
        List<SnsEvent> currentSubBatch = new ArrayList<>();
        int currentSize = 0;
        
        for (SnsEvent event : batch) {
            // Calculate payload size (UTF-8 bytes)
            int eventSize = event.payload() != null ? event.payload().getBytes(StandardCharsets.UTF_8).length : 0;
            
            // If adding this event exceeds limit, seal the current sub-batch
            // Also ensure we don't split if the sub-batch is empty (single large event case must fail downstream or be handled otherwise)
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

        List<PublishBatchRequestEntry> entries = batch.stream()
            .map(event -> PublishBatchRequestEntry.builder()
                .id(event.messageDeduplicationId())
                .messageGroupId(event.messageGroupId()) 
                .message(event.payload())
                .messageDeduplicationId(event.messageDeduplicationId())
                .build())
            .collect(Collectors.toList());

        PublishBatchRequest request = PublishBatchRequest.builder()
            .topicArn(topicArn)
            .publishBatchRequestEntries(entries)
            .build();

        return Mono.fromFuture(() -> snsClient.publishBatch(request))
            .retryWhen(Retry.backoff(3, Duration.ofMillis(100))
                .filter(throwable -> {
                    // Retry only on specific exceptions if needed, or all for now
                    // In production, filter for ThrottlingException, InternalErrorException, etc.
                    log.warn("Retrying batch publication due to error: {}", throwable.getMessage());
                    return true; 
                }))
            .doOnSuccess(response -> {
                if (response.hasFailed()) {
                    // SNS accepted the batch request but failed to process some individual messages.
                    // This breaks strict FIFO if we continue. 
                    // Depending on requirements, we might want to throw an exception here to stop the stream
                    // or implement a DLQ strategy.
                    log.error("Batch published with {} failures. Failed IDs: {}", 
                        response.failed().size(), 
                        response.failed().stream().map(f -> f.id() + ":" + f.message()).collect(Collectors.joining(", ")));
                } else {
                    log.debug("Successfully published batch of {} events.", batch.size());
                }
            })
            .doOnError(e -> log.error("Failed to publish batch after retries", e));
    }
}
