---
status: pending
priority: p2
issue_id: "006"
tags: [code-review, performance, reactive, data-integrity]
dependencies: []
---

# Missing Backpressure Handling at Stream Entry Point

## Problem Statement

The top-level backpressure buffer is commented out. Without entry-level backpressure control, if the upstream source emits faster than SNS can consume, the `groupBy` operator will accumulate unbounded state, potentially causing OOM.

**Why it matters:**
- `groupBy` maintains internal `Map<Integer, UnicastProcessor>` - unbounded growth
- At 100,000 events/sec, memory usage could reach 5GB+
- Stream termination and data loss under sustained load

## Findings

**Location:** `src/main/java/com/example/snspublisher/service/AsyncFifoSnsPublisher.java` (Lines 63-67)

```java
// Increased to 1M to withstand high throughput load testing bursts
// .onBackpressureBuffer(1000000)

// Distribute events into fixed partitions...
.groupBy(event -> Math.abs(event.messageGroupId().hashCode()) % partitionCount)
```

**Current mitigation (insufficient):** Line 76

```java
.onBackpressureBuffer(1000)  // Per partition only
```

**Evidence:**
- Agent: performance-oracle, data-integrity-guardian
- Commented code shows this was considered but removed
- Per-partition buffer of 1000 x 256 partitions = 256,000 max, but `groupBy` internal state is separate
- No overflow strategy defined

**Memory Projection:**
| Events/Second | Memory Usage | Risk Level |
|---------------|--------------|------------|
| 10,000 | ~500MB | Medium |
| 100,000 | ~5GB | **High - OOM likely** |
| 1,000,000 | Unbounded | **Critical** |

## Proposed Solutions

### Option 1: Add Top-Level Buffer with Error Strategy (Recommended)

```java
return eventStream
        .onBackpressureBuffer(100000, BufferOverflowStrategy.ERROR)
        .groupBy(...)
```

**Pros:** Clear failure mode, prevents OOM
**Cons:** Will error if buffer fills
**Effort:** Trivial
**Risk:** Low

### Option 2: Drop Oldest on Overflow

```java
.onBackpressureBuffer(100000,
    dropped -> log.warn("Dropped event due to backpressure: {}", dropped),
    BufferOverflowStrategy.DROP_OLDEST)
```

**Pros:** Graceful degradation, no OOM
**Cons:** Data loss (intentional), may violate FIFO
**Effort:** Trivial
**Risk:** Medium - business decision needed

### Option 3: Block Upstream

```java
.onBackpressureBuffer(100000, Duration.ofSeconds(30), () -> new RuntimeException("Backpressure timeout"))
```

**Pros:** Applies natural backpressure to upstream
**Cons:** May block caller threads
**Effort:** Small
**Risk:** Medium

### Option 4: Make Buffer Size Configurable

Add `backpressureBufferSize` to `SnsPublisherProperties`.

**Pros:** Allows tuning per deployment
**Cons:** Requires understanding by users
**Effort:** Small
**Risk:** Low

## Recommended Action

_To be filled during triage_

## Technical Details

**Affected files:**
- `src/main/java/com/example/snspublisher/service/AsyncFifoSnsPublisher.java`
- `src/main/java/com/example/snspublisher/config/SnsPublisherProperties.java` (if Option 4)

**Components:** publishEvents method

## Acceptance Criteria

- [ ] Stream entry point has backpressure handling
- [ ] Overflow behavior is defined and documented
- [ ] Memory usage is bounded under high load
- [ ] Integration test verifies behavior under backpressure

## Work Log

| Date | Action | Learnings |
|------|--------|-----------|
| 2026-01-20 | Created from code review | Identified by performance-oracle and data-integrity-guardian |

## Resources

- [Reactor Backpressure](https://projectreactor.io/docs/core/release/reference/#_on_backpressure_and_ways_to_reshape_requests)
