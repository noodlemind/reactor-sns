---
status: pending
priority: p2
issue_id: "010"
tags: [code-review, security, logging]
dependencies: []
---

# Information Leakage in Log Statements

## Problem Statement

The code logs full SNS response objects and complete exception stack traces, which may leak internal identifiers, AWS account information, ARNs, and other sensitive metadata.

**Why it matters:**
- AWS account IDs potentially exposed
- Internal message IDs leaked
- Attack surface mapping through error messages

## Findings

**Location 1:** `src/main/java/com/example/snspublisher/service/AsyncFifoSnsPublisher.java` (Lines 152-155)

```java
log.error("Batch published with {} failures. Successful: {}. Full Response: {}",
    response.failed().size(),
    response.successful().size(),
    response);  // LOGS ENTIRE RESPONSE OBJECT
```

**Location 2:** `src/main/java/com/example/snspublisher/service/AsyncFifoSnsPublisher.java` (Line 160)

```java
.doOnError(e -> log.error("Failed to publish batch after retries", e));
// Full exception with stack trace
```

**Evidence:**
- Agent: security-sentinel
- SNS response contains message IDs and metadata
- Exceptions may contain ARNs, account IDs, configuration details

## Proposed Solutions

### Option 1: Log Only Specific Fields (Recommended)

```java
log.error("Batch published with {} failures. Successful: {}. Failed IDs: {}",
    response.failed().size(),
    response.successful().size(),
    response.failed().stream()
        .map(BatchResultErrorEntry::id)
        .collect(Collectors.toList()));
```

**Pros:** Useful debugging info without leaking internals
**Cons:** May miss some useful context
**Effort:** Small
**Risk:** Low

### Option 2: Structured Logging with Redaction

Use structured logging (MDC) with explicit field selection.

**Pros:** Better log analysis, explicit about what's logged
**Cons:** More verbose code
**Effort:** Medium
**Risk:** Low

### Option 3: Configurable Log Level

Make verbose logging available at DEBUG level only.

```java
if (log.isDebugEnabled()) {
    log.debug("Full response: {}", response);
}
log.error("Batch had {} failures", response.failed().size());
```

**Pros:** Detailed info available when needed
**Cons:** Requires log level change to troubleshoot
**Effort:** Small
**Risk:** Low

## Recommended Action

_To be filled during triage_

## Technical Details

**Affected files:**
- `src/main/java/com/example/snspublisher/service/AsyncFifoSnsPublisher.java`

**Components:** publishBatch method logging

## Acceptance Criteria

- [ ] Full response objects not logged at INFO/ERROR level
- [ ] Exception messages logged without full stack trace at ERROR level
- [ ] Sensitive information (ARNs, account IDs) not exposed
- [ ] DEBUG level available for troubleshooting when needed

## Work Log

| Date | Action | Learnings |
|------|--------|-----------|
| 2026-01-20 | Created from code review | Identified by security-sentinel |

## Resources

- [OWASP Logging Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html)
