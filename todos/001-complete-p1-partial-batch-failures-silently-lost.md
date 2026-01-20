---
status: pending
priority: p1
issue_id: "001"
tags: [code-review, data-integrity, critical]
dependencies: []
---

# Partial Batch Failures Silently Lost - DATA LOSS

## Problem Statement

When SNS accepts a batch request but reports some individual messages as failed (via `response.failed()`), the code only logs the error and continues. Failed messages are permanently lost with no retry, dead-letter queue, or persistence mechanism.

**Why it matters:** This is silent data loss in production. Messages that fail within a batch are never delivered and never recovered.

## Findings

**Location:** `src/main/java/com/example/snspublisher/service/AsyncFifoSnsPublisher.java` (Lines 146-156)

```java
.doOnSuccess(response -> {
    if (response.failed() != null && !response.failed().isEmpty()) {
        log.error("Batch published with {} failures. Successful: {}. Full Response: {}",
                response.failed().size(),
                response.successful().size(),
                response);
        // LOGGED BUT NOT PROPAGATED - MESSAGES ARE LOST
    }
})
```

**Evidence:**
- Agent: data-integrity-guardian, architecture-strategist
- The `Mono` completes successfully even when messages fail
- No mechanism exists to retry, re-queue, or persist failed messages
- FIFO ordering can be violated if some messages in a sequence fail

## Proposed Solutions

### Option 1: Retry Failed Messages (Recommended)
Extract failed entries and retry them in a subsequent batch.

**Pros:** Maximizes delivery success, maintains data integrity
**Cons:** Adds complexity, may cause re-ordering within message groups
**Effort:** Medium
**Risk:** Low

### Option 2: Propagate Error to Caller
Convert partial failures into an exception that propagates to the caller.

**Pros:** Simple, caller can decide handling strategy
**Cons:** Breaks reactive stream on first partial failure
**Effort:** Small
**Risk:** Medium - may cause too many failures

### Option 3: Dead Letter Queue
Persist failed messages to a DLQ (SQS, database, or file) for later processing.

**Pros:** No data loss, decoupled retry handling
**Cons:** Requires additional infrastructure, more complex deployment
**Effort:** Large
**Risk:** Low

## Recommended Action

_To be filled during triage_

## Technical Details

**Affected files:**
- `src/main/java/com/example/snspublisher/service/AsyncFifoSnsPublisher.java`

**Components:** publishBatch method

## Acceptance Criteria

- [ ] When SNS reports failed messages in a batch response, they are not silently dropped
- [ ] Failed messages are either retried, propagated as errors, or persisted to DLQ
- [ ] Unit test verifies handling of partial batch failures
- [ ] Metrics/logging captures failed message count for monitoring

## Work Log

| Date | Action | Learnings |
|------|--------|-----------|
| 2026-01-20 | Created from code review | Identified by data-integrity-guardian and architecture-strategist agents |

## Resources

- [AWS SNS PublishBatch API](https://docs.aws.amazon.com/sns/latest/api/API_PublishBatch.html)
- Related finding: 002-pending-p1-scheduler-resource-leak.md
