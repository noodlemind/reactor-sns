package io.clype.reactorsns.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.clype.reactorsns.model.FifoOrderingViolationException;
import io.clype.reactorsns.model.PartialBatchFailureException;
import io.clype.reactorsns.model.SnsEvent;

import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import software.amazon.awssdk.services.sns.SnsAsyncClient;
import software.amazon.awssdk.services.sns.model.BatchResultErrorEntry;
import software.amazon.awssdk.services.sns.model.PublishBatchRequest;
import software.amazon.awssdk.services.sns.model.PublishBatchRequestEntry;
import software.amazon.awssdk.services.sns.model.PublishBatchResponse;
import software.amazon.awssdk.services.sns.model.PublishBatchResultEntry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AsyncFifoSnsPublisherTest {

    private SnsAsyncClient snsClient;
    private AsyncFifoSnsPublisher publisher;
    private final String TOPIC_ARN = "arn:aws:sns:us-east-1:123456789012:MyFifoTopic.fifo";

    @BeforeEach
    void setUp() {
        snsClient = mock(SnsAsyncClient.class);
        // Use a smaller partition count to force collisions and verify isolation
        publisher = new AsyncFifoSnsPublisher(snsClient, TOPIC_ARN, 10, Duration.ofMillis(50));
    }

    @Test
    void testSequenceMaintainedForThreeGroupsInterleaved() {
        // SETUP: Mock SNS Client to return success
        when(snsClient.publishBatch(any(PublishBatchRequest.class)))
            .thenAnswer(invocation -> {
                PublishBatchRequest req = invocation.getArgument(0);
                List<PublishBatchResultEntry> resultEntries = req.publishBatchRequestEntries().stream()
                    .map(e -> PublishBatchResultEntry.builder().id(e.id()).build())
                    .collect(Collectors.toList());
                return CompletableFuture.completedFuture(PublishBatchResponse.builder()
                    .successful(resultEntries)
                    .build());
            });

        // SCENARIO: 3 Groups, 20 events each.
        // We simulate a stream that is interleaved (A1, B1, C1, A2, B2, C2...)
        int eventsPerGroup = 20;
        List<String> groupIds = List.of("Group-Alpha", "Group-Beta", "Group-Gamma");
        List<SnsEvent> inputEvents = new ArrayList<>();

        for (int i = 0; i < eventsPerGroup; i++) {
            for (String groupId : groupIds) {
                // Sequence number is 'i'
                inputEvents.add(new SnsEvent(
                    groupId,
                    groupId + "-dedup-" + i,
                    "payload-" + i
                ));
            }
        }

        Flux<SnsEvent> eventStream = Flux.fromIterable(inputEvents);

        // ACTION: Publish events
        StepVerifier.create(publisher.publishEvents(eventStream))
            // We expect: (60 events total) / (batch size 10) = ~6 batches if perfectly efficient,
            // but due to partitioning and buffering timing, might be more.
            // We just verify completion and then check the captured requests.
            .expectNextCount(6)
            .expectComplete()
            .verify(Duration.ofSeconds(5));

        // VERIFICATION: Inspect what was sent to SNS
        ArgumentCaptor<PublishBatchRequest> captor = ArgumentCaptor.forClass(PublishBatchRequest.class);
        verify(snsClient, atLeast(1)).publishBatch(captor.capture());

        List<PublishBatchRequest> sentRequests = captor.getAllValues();

        // Reconstruct the timeline for each group from the batches
        Map<String, List<Integer>> groupSequences = new HashMap<>();

        for (PublishBatchRequest req : sentRequests) {
            // Note: A single batch might contain mixed groups if they hashed to the same partition
            // and were buffered together. This is allowed in SNS FIFO.
            for (PublishBatchRequestEntry entry : req.publishBatchRequestEntries()) {
                String groupId = entry.messageGroupId();
                // Extract sequence from payload, format is "payload-X"
                String payload = entry.message();
                int sequence = Integer.parseInt(payload.replace("payload-", ""));

                groupSequences.computeIfAbsent(groupId, k -> new ArrayList<>()).add(sequence);
            }
        }

        // Assertions
        for (String groupId : groupIds) {
            List<Integer> sequence = groupSequences.get(groupId);

            assertEquals(eventsPerGroup, sequence.size(), "Should have all events for " + groupId);

            // Verify strict ascending order: 0, 1, 2, ... 19
            for (int i = 0; i < eventsPerGroup; i++) {
                assertEquals(i, sequence.get(i),
                    "Sequence mismatch for " + groupId + " at index " + i);
            }
        }
    }

    @Test
    void testEventsAreBatchedUpToTen() {
        // SETUP: Strict Mock behaving like real AWS SNS
        // AWS SNS throws an exception if a batch has more than 10 entries.
        when(snsClient.publishBatch(any(PublishBatchRequest.class)))
            .thenAnswer(invocation -> {
                PublishBatchRequest req = invocation.getArgument(0);
                if (req.publishBatchRequestEntries().size() > 10) {
                    throw new RuntimeException("AWS SNS Exception: Batch size limit exceeded (max 10)");
                }
                return CompletableFuture.completedFuture(PublishBatchResponse.builder().build());
            });

        // SCENARIO: Single group, 105 events.
        // The Application code MUST split this into chunks of 10.
        // If it tries to send 20 or 100 at once, the Mock above will throw an exception and fail the test.
        List<SnsEvent> events = new ArrayList<>();
        for (int i = 0; i < 105; i++) {
            events.add(new SnsEvent("GroupZ", "id-" + i, "payload-" + i));
        }

        Flux<SnsEvent> eventStream = Flux.fromIterable(events);

        // ACTION
        StepVerifier.create(publisher.publishEvents(eventStream))
            .expectNextCount(11) // 10 full batches + 1 partial
            .verifyComplete();

        // VERIFICATION
        ArgumentCaptor<PublishBatchRequest> captor = ArgumentCaptor.forClass(PublishBatchRequest.class);
        verify(snsClient, times(11)).publishBatch(captor.capture());

        List<PublishBatchRequest> requests = captor.getAllValues();

        // Count full batches
        long fullBatches = requests.stream()
            .filter(req -> req.publishBatchRequestEntries().size() == 10)
            .count();

        long partialBatches = requests.stream()
            .filter(req -> req.publishBatchRequestEntries().size() == 5)
            .count();

        assertEquals(10, fullBatches, "Should have 10 full batches of 10");
        assertEquals(1, partialBatches, "Should have 1 partial batch of 5");
    }

    // ==========================================================================
    // Partial Batch Failure Retry Tests
    // ==========================================================================

    @Test
    void testPartialBatchFailureWithThrottlingIsRetried() {
        AtomicInteger attemptNumber = new AtomicInteger(0);

        when(snsClient.publishBatch(any(PublishBatchRequest.class)))
            .thenAnswer(invocation -> {
                if (attemptNumber.incrementAndGet() == 1) {
                    // First attempt: partial failure with throttling
                    return CompletableFuture.completedFuture(
                        PublishBatchResponse.builder()
                            .successful(List.of(
                                PublishBatchResultEntry.builder().id("dedup-0-0").build()))
                            .failed(List.of(
                                BatchResultErrorEntry.builder()
                                    .id("dedup-1-1")
                                    .code("Throttling")
                                    .message("Rate exceeded")
                                    .senderFault(false)
                                    .build()))
                            .build());
                }
                // Second attempt: success
                return CompletableFuture.completedFuture(
                    PublishBatchResponse.builder()
                        .successful(List.of(
                            PublishBatchResultEntry.builder().id("dedup-0-0").build(),
                            PublishBatchResultEntry.builder().id("dedup-1-1").build()))
                        .build());
            });

        List<SnsEvent> events = List.of(
            new SnsEvent("group1", "dedup-0", "payload-0"),
            new SnsEvent("group1", "dedup-1", "payload-1")
        );

        StepVerifier.create(publisher.publishEvents(Flux.fromIterable(events)))
            .expectNextCount(1)
            .verifyComplete();

        verify(snsClient, times(2)).publishBatch(any(PublishBatchRequest.class));
    }

    @Test
    void testPartialBatchFailureWithServiceUnavailableIsRetried() {
        AtomicInteger attemptNumber = new AtomicInteger(0);

        when(snsClient.publishBatch(any(PublishBatchRequest.class)))
            .thenAnswer(invocation -> {
                if (attemptNumber.incrementAndGet() == 1) {
                    return CompletableFuture.completedFuture(
                        PublishBatchResponse.builder()
                            .failed(List.of(
                                BatchResultErrorEntry.builder()
                                    .id("dedup-0-0")
                                    .code("ServiceUnavailable")
                                    .message("Service temporarily unavailable")
                                    .senderFault(false)
                                    .build()))
                            .build());
                }
                return CompletableFuture.completedFuture(
                    PublishBatchResponse.builder()
                        .successful(List.of(
                            PublishBatchResultEntry.builder().id("dedup-0-0").build()))
                        .build());
            });

        List<SnsEvent> events = List.of(new SnsEvent("group1", "dedup-0", "payload-0"));

        StepVerifier.create(publisher.publishEvents(Flux.fromIterable(events)))
            .expectNextCount(1)
            .verifyComplete();

        verify(snsClient, times(2)).publishBatch(any(PublishBatchRequest.class));
    }

    @Test
    void testPartialBatchFailureWithSenderFaultIsNotRetried() {
        when(snsClient.publishBatch(any(PublishBatchRequest.class)))
            .thenAnswer(invocation -> CompletableFuture.completedFuture(
                PublishBatchResponse.builder()
                    .failed(List.of(
                        BatchResultErrorEntry.builder()
                            .id("dedup-0-0")
                            .code("InvalidParameter")
                            .message("Message too long")
                            .senderFault(true)
                            .build()))
                    .build()));

        List<SnsEvent> events = List.of(new SnsEvent("group1", "dedup-0", "x".repeat(300000)));

        StepVerifier.create(publisher.publishEvents(Flux.fromIterable(events)))
            .expectError(PartialBatchFailureException.class)
            .verify();

        verify(snsClient, times(1)).publishBatch(any(PublishBatchRequest.class));
    }

    @Test
    void testPartialBatchFailureWithMixedErrorsIsNotRetried() {
        // Batch has both retryable (Throttling) and non-retryable (InvalidParameter) errors
        // Should NOT retry because not ALL failures are retryable
        when(snsClient.publishBatch(any(PublishBatchRequest.class)))
            .thenAnswer(invocation -> CompletableFuture.completedFuture(
                PublishBatchResponse.builder()
                    .failed(List.of(
                        BatchResultErrorEntry.builder()
                            .id("dedup-0-0")
                            .code("Throttling")
                            .message("Rate exceeded")
                            .senderFault(false)
                            .build(),
                        BatchResultErrorEntry.builder()
                            .id("dedup-1-1")
                            .code("InvalidParameter")
                            .message("Invalid message")
                            .senderFault(true)
                            .build()))
                    .build()));

        List<SnsEvent> events = List.of(
            new SnsEvent("group1", "dedup-0", "payload-0"),
            new SnsEvent("group1", "dedup-1", "invalid")
        );

        StepVerifier.create(publisher.publishEvents(Flux.fromIterable(events)))
            .expectError(PartialBatchFailureException.class)
            .verify();

        // Should NOT retry because one error has senderFault=true
        verify(snsClient, times(1)).publishBatch(any(PublishBatchRequest.class));
    }

    @Test
    void testRetryExhaustionAfterMaxAttempts() {
        // Always return throttling error (senderFault=false)
        when(snsClient.publishBatch(any(PublishBatchRequest.class)))
            .thenAnswer(invocation -> CompletableFuture.completedFuture(
                PublishBatchResponse.builder()
                    .failed(List.of(
                        BatchResultErrorEntry.builder()
                            .id("dedup-0-0")
                            .code("Throttling")
                            .message("Rate exceeded")
                            .senderFault(false)
                            .build()))
                    .build()));

        List<SnsEvent> events = List.of(new SnsEvent("group1", "dedup-0", "payload-0"));

        // After retries exhausted, Reactor wraps in RetryExhaustedException with cause PartialBatchFailureException
        StepVerifier.create(publisher.publishEvents(Flux.fromIterable(events)))
            .expectErrorMatches(e ->
                Exceptions.isRetryExhausted(e) &&
                e.getCause() instanceof PartialBatchFailureException)
            .verify(Duration.ofSeconds(30));

        // 1 initial + 3 retries (MAX_RETRIES) = 4 total calls
        verify(snsClient, times(4)).publishBatch(any(PublishBatchRequest.class));
    }

    // ==========================================================================
    // FIFO Ordering Violation Tests
    // ==========================================================================

    @Test
    void testFifoViolationDetectedWhenSuccessAfterFailure() {
        // Scenario: batch [A, B, C] where A succeeds (pos 0), B fails (pos 1), C succeeds (pos 2)
        // This is a FIFO violation because C was delivered before B
        when(snsClient.publishBatch(any(PublishBatchRequest.class)))
            .thenAnswer(invocation -> CompletableFuture.completedFuture(
                PublishBatchResponse.builder()
                    .successful(List.of(
                        PublishBatchResultEntry.builder().id("dedup-0-0").build(),
                        PublishBatchResultEntry.builder().id("dedup-2-2").build()))
                    .failed(List.of(
                        BatchResultErrorEntry.builder()
                            .id("dedup-1-1")
                            .code("Throttling")
                            .message("Rate exceeded")
                            .senderFault(false)
                            .build()))
                    .build()));

        List<SnsEvent> events = List.of(
            new SnsEvent("group1", "dedup-0", "payload-0"),
            new SnsEvent("group1", "dedup-1", "payload-1"),
            new SnsEvent("group1", "dedup-2", "payload-2")
        );

        StepVerifier.create(publisher.publishEvents(Flux.fromIterable(events)))
            .expectError(FifoOrderingViolationException.class)
            .verify();

        // Should NOT retry - FIFO violation is not retryable
        verify(snsClient, times(1)).publishBatch(any(PublishBatchRequest.class));
    }

    @Test
    void testNoFifoViolationWhenAllFailuresAfterSuccesses() {
        // Scenario: batch [A, B, C] where A succeeds (pos 0), B fails (pos 1), C fails (pos 2)
        // This is NOT a FIFO violation - all failures are after all successes
        // Safe to retry because no message was delivered out of order
        AtomicInteger attemptNumber = new AtomicInteger(0);

        when(snsClient.publishBatch(any(PublishBatchRequest.class)))
            .thenAnswer(invocation -> {
                if (attemptNumber.incrementAndGet() == 1) {
                    return CompletableFuture.completedFuture(
                        PublishBatchResponse.builder()
                            .successful(List.of(
                                PublishBatchResultEntry.builder().id("dedup-0-0").build()))
                            .failed(List.of(
                                BatchResultErrorEntry.builder()
                                    .id("dedup-1-1")
                                    .code("Throttling")
                                    .message("Rate exceeded")
                                    .senderFault(false)
                                    .build(),
                                BatchResultErrorEntry.builder()
                                    .id("dedup-2-2")
                                    .code("Throttling")
                                    .message("Rate exceeded")
                                    .senderFault(false)
                                    .build()))
                            .build());
                }
                // Retry succeeds
                return CompletableFuture.completedFuture(
                    PublishBatchResponse.builder()
                        .successful(List.of(
                            PublishBatchResultEntry.builder().id("dedup-0-0").build(),
                            PublishBatchResultEntry.builder().id("dedup-1-1").build(),
                            PublishBatchResultEntry.builder().id("dedup-2-2").build()))
                        .build());
            });

        List<SnsEvent> events = List.of(
            new SnsEvent("group1", "dedup-0", "payload-0"),
            new SnsEvent("group1", "dedup-1", "payload-1"),
            new SnsEvent("group1", "dedup-2", "payload-2")
        );

        StepVerifier.create(publisher.publishEvents(Flux.fromIterable(events)))
            .expectNextCount(1)
            .verifyComplete();

        // Should retry because no FIFO violation
        verify(snsClient, times(2)).publishBatch(any(PublishBatchRequest.class));
    }

    @Test
    void testBatchesSplitByMessageGroupId() {
        // Verify that messages with different messageGroupIds are sent in separate batches
        // This prevents FIFO violations across groups
        ArgumentCaptor<PublishBatchRequest> captor = ArgumentCaptor.forClass(PublishBatchRequest.class);

        when(snsClient.publishBatch(any(PublishBatchRequest.class)))
            .thenAnswer(invocation -> {
                PublishBatchRequest req = invocation.getArgument(0);
                List<PublishBatchResultEntry> results = req.publishBatchRequestEntries().stream()
                    .map(e -> PublishBatchResultEntry.builder().id(e.id()).build())
                    .collect(Collectors.toList());
                return CompletableFuture.completedFuture(
                    PublishBatchResponse.builder().successful(results).build());
            });

        // Send interleaved messages from 3 different groups
        List<SnsEvent> events = List.of(
            new SnsEvent("Loan-A", "dedup-A1", "payload-A1"),
            new SnsEvent("Loan-B", "dedup-B1", "payload-B1"),
            new SnsEvent("Loan-A", "dedup-A2", "payload-A2"),
            new SnsEvent("Loan-C", "dedup-C1", "payload-C1"),
            new SnsEvent("Loan-B", "dedup-B2", "payload-B2")
        );

        StepVerifier.create(publisher.publishEvents(Flux.fromIterable(events)))
            .expectNextCount(3)  // 3 batches (one per messageGroupId)
            .verifyComplete();

        verify(snsClient, atLeast(3)).publishBatch(captor.capture());

        // Verify each batch contains only one messageGroupId
        for (PublishBatchRequest req : captor.getAllValues()) {
            List<String> groupIds = req.publishBatchRequestEntries().stream()
                .map(PublishBatchRequestEntry::messageGroupId)
                .distinct()
                .collect(Collectors.toList());

            assertEquals(1, groupIds.size(),
                "Each batch should contain only one messageGroupId, but found: " + groupIds);
        }
    }

    @Test
    void testBatch2NotAttemptedWhenBatch1FailsForSameGroup() {
        // Scenario: Group A has 15 messages (Batch 1: first 10, Batch 2: remaining 5)
        // Batch 1 fails permanently → Batch 2 should NEVER be attempted
        // This ensures strict FIFO ordering within a messageGroupId
        AtomicInteger batchCount = new AtomicInteger(0);

        when(snsClient.publishBatch(any(PublishBatchRequest.class)))
            .thenAnswer(invocation -> {
                batchCount.incrementAndGet();
                // Always return sender fault (non-retryable) so batch fails permanently
                return CompletableFuture.completedFuture(
                    PublishBatchResponse.builder()
                        .failed(List.of(
                            BatchResultErrorEntry.builder()
                                .id("id-0")
                                .code("InvalidParameter")
                                .message("Invalid message")
                                .senderFault(true)  // Non-retryable
                                .build()))
                        .build());
            });

        // Create 15 events for the same group (will be split into 2 batches: 10 + 5)
        List<SnsEvent> events = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            events.add(new SnsEvent("Loan-123", "dedup-" + i, "payload-" + i));
        }

        StepVerifier.create(publisher.publishEvents(Flux.fromIterable(events)))
            .expectError(PartialBatchFailureException.class)
            .verify();

        // Critical assertion: Only Batch 1 should be attempted
        // Batch 2 should NOT be attempted because Batch 1 failed
        assertEquals(1, batchCount.get(),
            "Only the first batch should be attempted. Batch 2 should not be attempted when Batch 1 fails.");
    }

    @Test
    void testDifferentGroupsContinueWhenOneGroupFails() {
        // Scenario: Group A fails, but Groups B and C should still be processed
        // This verifies independent processing across different messageGroupIds
        AtomicInteger callCount = new AtomicInteger(0);

        when(snsClient.publishBatch(any(PublishBatchRequest.class)))
            .thenAnswer(invocation -> {
                PublishBatchRequest req = invocation.getArgument(0);
                String groupId = req.publishBatchRequestEntries().get(0).messageGroupId();
                callCount.incrementAndGet();

                if ("Loan-A".equals(groupId)) {
                    // Group A fails with sender fault (non-retryable)
                    return CompletableFuture.completedFuture(
                        PublishBatchResponse.builder()
                            .failed(List.of(
                                BatchResultErrorEntry.builder()
                                    .id("id-0")
                                    .code("InvalidParameter")
                                    .message("Invalid")
                                    .senderFault(true)
                                    .build()))
                            .build());
                }
                // Groups B and C succeed
                List<PublishBatchResultEntry> results = req.publishBatchRequestEntries().stream()
                    .map(e -> PublishBatchResultEntry.builder().id(e.id()).build())
                    .collect(Collectors.toList());
                return CompletableFuture.completedFuture(
                    PublishBatchResponse.builder().successful(results).build());
            });

        List<SnsEvent> events = List.of(
            new SnsEvent("Loan-A", "dedup-A1", "payload-A1"),
            new SnsEvent("Loan-B", "dedup-B1", "payload-B1"),
            new SnsEvent("Loan-C", "dedup-C1", "payload-C1")
        );

        // With flatMap, successful groups emit their responses before the failed group errors
        // Expect 2 successes (B and C) then an error (A)
        StepVerifier.create(publisher.publishEvents(Flux.fromIterable(events)))
            .expectNextCount(2)  // B and C succeed
            .expectError(PartialBatchFailureException.class)  // A fails
            .verify();

        // All three groups should have been attempted (processed in parallel)
        assertEquals(3, callCount.get(),
            "All three groups (A, B, C) should be attempted independently");
    }

    // ==========================================================================
    // Configuration Validation Tests
    // ==========================================================================

    @Test
    void testPartitionCountUpperBound() {
        assertThrows(IllegalArgumentException.class, () ->
            new AsyncFifoSnsPublisher(snsClient, TOPIC_ARN, 5000, Duration.ofMillis(10)),
            "partitionCount exceeding 4096 should throw IllegalArgumentException");
    }

    @Test
    void testBufferSizeUpperBound() {
        assertThrows(IllegalArgumentException.class, () ->
            new AsyncFifoSnsPublisher(snsClient, TOPIC_ARN, 256, Duration.ofMillis(10),
                2_000_000, 100, null),
            "bufferSize exceeding 1,000,000 should throw IllegalArgumentException");
    }

    @Test
    void testPartitionBufferSizeUpperBound() {
        assertThrows(IllegalArgumentException.class, () ->
            new AsyncFifoSnsPublisher(snsClient, TOPIC_ARN, 256, Duration.ofMillis(10),
                10_000, 20_000, null),
            "partitionBufferSize exceeding 10,000 should throw IllegalArgumentException");
    }

    @Test
    void testValidConfigurationAtUpperBounds() {
        // Should not throw - valid configuration at max values
        AsyncFifoSnsPublisher validPublisher = new AsyncFifoSnsPublisher(
            snsClient, TOPIC_ARN, 4096, Duration.ofMillis(10),
            1_000_000, 10_000, null);
        validPublisher.destroy();
    }

    // ==========================================================================
    // Generic Converter Tests
    // ==========================================================================

    record TestDomainEvent(String groupId, String dedupId, String data) {}

    @Test
    void publishEventsWithConverter() {
        when(snsClient.publishBatch(any(PublishBatchRequest.class)))
            .thenAnswer(invocation -> {
                PublishBatchRequest req = invocation.getArgument(0);
                List<PublishBatchResultEntry> results = req.publishBatchRequestEntries().stream()
                    .map(e -> PublishBatchResultEntry.builder().id(e.id()).build())
                    .collect(Collectors.toList());
                return CompletableFuture.completedFuture(
                    PublishBatchResponse.builder().successful(results).build());
            });

        List<TestDomainEvent> events = List.of(
            new TestDomainEvent("loan-123", "event-1", "payload-1"),
            new TestDomainEvent("loan-123", "event-2", "payload-2")
        );

        StepVerifier.create(publisher.publishEvents(
                Flux.fromIterable(events),
                e -> new SnsEvent(e.groupId(), e.dedupId(), e.data())))
            .expectNextCount(1)
            .verifyComplete();

        verify(snsClient, times(1)).publishBatch(any(PublishBatchRequest.class));
    }

    @Test
    void publishEventsWithConverterRejectsNull() {
        assertThrows(NullPointerException.class, () ->
            publisher.publishEvents(Flux.just("x"), null));

        assertThrows(NullPointerException.class, () ->
            publisher.publishEvents(null, x -> new SnsEvent("g", "d", "p")));
    }
}
