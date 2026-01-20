package com.example.snspublisher.service;

import com.example.snspublisher.model.SnsEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.sns.SnsAsyncClient;
import software.amazon.awssdk.services.sns.model.PublishBatchRequest;
import software.amazon.awssdk.services.sns.model.PublishBatchRequestEntry;
import software.amazon.awssdk.services.sns.model.PublishBatchResponse;
import software.amazon.awssdk.services.sns.model.PublishBatchResultEntry;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

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
                    "payload-" + i, 
                    String.valueOf(i)
                ));
            }
        }

        System.out.println("Input Stream size: " + inputEvents.size());
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
        System.out.println("Total batches sent: " + sentRequests.size());

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
            System.out.println("Verifying sequence for " + groupId + ": " + sequence);
            
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
            events.add(new SnsEvent("GroupZ", "id-" + i, "payload-" + i, String.valueOf(i)));
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
}
