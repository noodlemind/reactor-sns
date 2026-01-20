---
status: pending
priority: p2
issue_id: "005"
tags: [code-review, performance, error-handling]
dependencies: []
---

# Retry Logic Retries All Exceptions Indiscriminately

## Problem Statement

The retry filter returns `true` for all exceptions, causing unnecessary retries on non-transient errors like `ValidationException`, `InvalidParameterException`, and `AuthorizationErrorException`. This wastes resources and delays failure detection.

**Why it matters:**
- 3 retries with 100ms backoff = 700ms+ wasted per non-retryable failure
- Each retry is a billable AWS API call
- Retry storms during outages amplify load

## Findings

**Location:** `src/main/java/com/example/snspublisher/service/AsyncFifoSnsPublisher.java` (Lines 139-145)

```java
.retryWhen(Retry.backoff(3, Duration.ofMillis(100))
        .filter(throwable -> {
            // Retry only on specific exceptions if needed, or all for now
            log.warn("Retrying batch publication due to error: {}", throwable.getMessage());
            return true;  // RETRIES ALL EXCEPTIONS
        }))
```

**Evidence:**
- Agent: security-sentinel, performance-oracle, architecture-strategist, pattern-recognition-specialist
- Comment acknowledges this is not production-ready
- No jitter in backoff (thundering herd risk)
- No maximum backoff cap

**Non-retryable exceptions that will be retried:**
- `ValidationException` - Invalid message format
- `InvalidParameterException` - Bad request
- `AuthorizationErrorException` - Permission denied
- `NotFoundException` - Topic doesn't exist

## Proposed Solutions

### Option 1: Filter Specific Retryable Exceptions (Recommended)

```java
.retryWhen(Retry.backoff(3, Duration.ofMillis(100))
        .maxBackoff(Duration.ofSeconds(5))
        .jitter(0.5)
        .filter(throwable ->
            throwable instanceof ThrottlingException ||
            throwable instanceof InternalErrorException ||
            throwable instanceof ServiceUnavailableException)
        .doBeforeRetry(signal ->
            log.warn("Retrying batch: {}", signal.failure().getMessage())))
```

**Pros:** Only retries transient errors, includes jitter and max backoff
**Cons:** Must identify all retryable exception types
**Effort:** Small
**Risk:** Low

### Option 2: Use AWS SDK Retry Policy

Leverage the AWS SDK's built-in retry mechanism and remove manual retry.

**Pros:** AWS knows best what to retry
**Cons:** Less control, may not integrate well with Reactor
**Effort:** Medium
**Risk:** Medium

### Option 3: Circuit Breaker Pattern

Add Resilience4j circuit breaker around the publish operation.

**Pros:** Prevents retry storms, graceful degradation
**Cons:** Additional dependency, more complexity
**Effort:** Medium
**Risk:** Low

## Recommended Action

_To be filled during triage_

## Technical Details

**Affected files:**
- `src/main/java/com/example/snspublisher/service/AsyncFifoSnsPublisher.java`

**Components:** publishBatch method

## Acceptance Criteria

- [ ] Retry only targets transient/throttling exceptions
- [ ] Non-retryable exceptions fail immediately
- [ ] Backoff includes jitter to prevent thundering herd
- [ ] Maximum backoff is capped
- [ ] Unit test verifies correct retry behavior

## Work Log

| Date | Action | Learnings |
|------|--------|-----------|
| 2026-01-20 | Created from code review | Identified by multiple agents |

## Resources

- [Reactor Retry documentation](https://projectreactor.io/docs/core/release/reference/#retries)
- [AWS SDK Retries](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/retries.html)
