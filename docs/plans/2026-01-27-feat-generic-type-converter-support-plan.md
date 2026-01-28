---
title: Generic Type Converter Support for Custom Record Types
type: feat
date: 2026-01-27
---

# feat: Generic Type Converter Support for Custom Record Types

## Problem

Consumers have custom domain record types (e.g., `SplitterPublisherRecord`) that need to be converted to `SnsEvent` before publishing. Currently they call `.map()` themselves:

```java
publisher.publishEvents(records.map(r -> new SnsEvent(
    r.getLoanId(),
    r.getBusinessEventId(),
    r.getPublishingPayload()
)));
```

## Solution

Add a convenience overload that accepts the converter as a parameter:

```java
publisher.publishEvents(records, r -> new SnsEvent(
    r.getLoanId(),
    r.getBusinessEventId(),
    r.getPublishingPayload()
));
```

## Implementation

### AsyncFifoSnsPublisher.java

Add after existing `publishEvents` method (line ~332):

```java
/**
 * Publishes domain events after converting each to {@link SnsEvent}.
 *
 * @param <T> the domain event type
 * @param eventStream the stream of events to publish
 * @param toSnsEvent function to convert each event to SnsEvent
 * @return a Flux of batch responses
 * @throws NullPointerException if either parameter is null
 */
public <T> Flux<PublishBatchResponse> publishEvents(
        Flux<T> eventStream,
        Function<T, SnsEvent> toSnsEvent) {
    Objects.requireNonNull(eventStream, "eventStream");
    Objects.requireNonNull(toSnsEvent, "toSnsEvent");
    return publishEvents(eventStream.map(toSnsEvent));
}
```

### AsyncFifoSnsPublisherTest.java

Add two tests:

```java
record TestDomainEvent(String groupId, String dedupId, String data) {}

@Test
void publishEventsWithConverter() {
    List<TestDomainEvent> events = List.of(
        new TestDomainEvent("loan-123", "event-1", "payload-1"),
        new TestDomainEvent("loan-123", "event-2", "payload-2")
    );

    publisher.publishEvents(
        Flux.fromIterable(events),
        e -> new SnsEvent(e.groupId(), e.dedupId(), e.data())
    ).blockLast();

    verify(snsClient, times(1)).publishBatch(any(PublishBatchRequest.class));
}

@Test
void publishEventsWithConverterRejectsNull() {
    assertThatThrownBy(() -> publisher.publishEvents(Flux.just("x"), null))
        .isInstanceOf(NullPointerException.class);

    assertThatThrownBy(() -> publisher.publishEvents(null, x -> new SnsEvent("g", "d", "p")))
        .isInstanceOf(NullPointerException.class);
}
```

## Files to Modify

| File | Change |
|------|--------|
| `src/main/java/io/clype/reactorsns/service/AsyncFifoSnsPublisher.java` | Add overloaded method |
| `src/test/java/io/clype/reactorsns/service/AsyncFifoSnsPublisherTest.java` | Add 2 tests |

## Acceptance Criteria

- [ ] New method accepts `Flux<T>` and `Function<T, SnsEvent>`
- [ ] Null parameters throw `NullPointerException`
- [ ] Existing behavior unchanged (backwards compatible)
- [ ] All existing tests pass
