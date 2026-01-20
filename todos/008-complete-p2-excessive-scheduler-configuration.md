---
status: pending
priority: p2
issue_id: "008"
tags: [code-review, performance, resource-management]
dependencies: ["002"]
---

# Excessive Scheduler Thread Pool Configuration

## Problem Statement

The bounded elastic scheduler is configured with 500 threads and 10,000 queued tasks, but actual concurrency is limited by `partitionCount` (default 256) in the `flatMap`. This wastes ~250 threads and reserves ~500MB of thread stack memory unnecessarily.

**Why it matters:**
- Each thread stack: ~1MB (default JVM stack size)
- 500 threads = 500MB reserved just for stacks
- Actual usage is capped at partitionCount (256)

## Findings

**Location:** `src/main/java/com/example/snspublisher/service/AsyncFifoSnsPublisher.java` (Line 45)

```java
this.ioScheduler = Schedulers.newBoundedElastic(500, 10000, "sns-publisher-io");
```

**Concurrency limit (Line 78):**

```java
.flatMap(partitionFlux -> ..., partitionCount); // Max concurrency = partitionCount (256)
```

**Analysis:**
- Scheduler allows 500 threads
- flatMap limits to 256 concurrent subscriptions
- ~244 threads will never be used
- Queue of 10,000 per thread = 5,000,000 potential queued tasks

**Evidence:**
- Agent: performance-oracle, security-sentinel

## Proposed Solutions

### Option 1: Size Scheduler to Match Concurrency (Recommended)

```java
this.ioScheduler = Schedulers.newBoundedElastic(
    partitionCount + 50,   // headroom for retries
    partitionCount * 10,   // bounded queue
    "sns-publisher-io"
);
```

**Pros:** Right-sized, saves ~250MB memory
**Cons:** None
**Effort:** Trivial
**Risk:** Low

### Option 2: Use Shared Bounded Elastic

```java
this.ioScheduler = Schedulers.boundedElastic();
```

**Pros:** No management needed, shared resource
**Cons:** Less isolation, may affect other reactive operations
**Effort:** Trivial
**Risk:** Medium

### Option 3: Make Configurable

Add `schedulerThreads` and `schedulerQueueSize` to properties.

**Pros:** Allows tuning per deployment
**Cons:** More complexity, users need to understand
**Effort:** Small
**Risk:** Low

## Recommended Action

_To be filled during triage_

## Technical Details

**Affected files:**
- `src/main/java/com/example/snspublisher/service/AsyncFifoSnsPublisher.java`

**Components:** AsyncFifoSnsPublisher constructor

**Note:** This should be addressed together with #002 (scheduler lifecycle)

## Acceptance Criteria

- [ ] Scheduler thread count matches actual concurrency needs
- [ ] Memory footprint reduced
- [ ] No change in throughput capability
- [ ] Configuration is either fixed appropriately or made configurable

## Work Log

| Date | Action | Learnings |
|------|--------|-----------|
| 2026-01-20 | Created from code review | Identified by performance-oracle |

## Resources

- [Reactor Schedulers](https://projectreactor.io/docs/core/release/reference/#schedulers)
