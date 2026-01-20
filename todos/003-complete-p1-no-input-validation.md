---
status: pending
priority: p1
issue_id: "003"
tags: [code-review, security, validation, critical]
dependencies: []
---

# No Input Validation on SnsEvent Fields

## Problem Statement

The `SnsEvent` record accepts any string values without validation. Null values, invalid characters, or oversized payloads will cause runtime failures at AWS API level after network transmission, or cause `NullPointerException` during processing.

**Why it matters:**
- Null `messageGroupId` causes NPE at line 70 during hash calculation
- Invalid characters rejected by AWS after transmission (wasted resources)
- Oversized payloads fail silently

## Findings

**Location:** `src/main/java/com/example/snspublisher/model/SnsEvent.java` (Lines 1-8)

```java
public record SnsEvent(
    String messageGroupId,          // Can be null - causes NPE
    String messageDeduplicationId,  // Can be null - causes AWS rejection
    String payload,                 // Can be null - handled, but >256KB not checked
    String sequenceNumber           // Unused field
) {}
```

**Additional NPE Risk Location:** `src/main/java/com/example/snspublisher/service/AsyncFifoSnsPublisher.java` (Line 70)

```java
.groupBy(event -> Math.abs(event.messageGroupId().hashCode()) % partitionCount)
// If messageGroupId is null, NPE here
```

**AWS Constraints not enforced:**
- `messageGroupId`: Max 128 chars, alphanumeric plus `.-_`
- `messageDeduplicationId`: Max 128 chars
- `payload`: Max 256KB per message

**Evidence:**
- Agent: security-sentinel
- No validation in record or factory method
- No @NotNull annotations
- No length or format checks

## Proposed Solutions

### Option 1: Add Validation in Factory Method (Recommended)
Create a static factory method with validation.

```java
public record SnsEvent(
    String messageGroupId,
    String messageDeduplicationId,
    String payload
) {
    public static SnsEvent of(String messageGroupId, String deduplicationId, String payload) {
        Objects.requireNonNull(messageGroupId, "messageGroupId cannot be null");
        Objects.requireNonNull(deduplicationId, "deduplicationId cannot be null");
        if (messageGroupId.length() > 128) {
            throw new IllegalArgumentException("messageGroupId exceeds 128 characters");
        }
        return new SnsEvent(messageGroupId, deduplicationId, payload);
    }
}
```

**Pros:** Fails fast, clear error messages
**Cons:** Callers must use factory method
**Effort:** Small
**Risk:** Low

### Option 2: Compact Constructor Validation
Use record's compact constructor for validation.

```java
public record SnsEvent(String messageGroupId, String messageDeduplicationId, String payload) {
    public SnsEvent {
        Objects.requireNonNull(messageGroupId, "messageGroupId required");
        Objects.requireNonNull(messageDeduplicationId, "deduplicationId required");
    }
}
```

**Pros:** Always enforced, cannot bypass
**Cons:** Cannot provide default values
**Effort:** Small
**Risk:** Low

### Option 3: Defensive Null Handling in Publisher
Handle nulls in the publisher rather than requiring valid input.

**Pros:** More forgiving API
**Cons:** Masks problems, violates fail-fast principle
**Effort:** Small
**Risk:** Medium - delays error detection

## Recommended Action

_To be filled during triage_

## Technical Details

**Affected files:**
- `src/main/java/com/example/snspublisher/model/SnsEvent.java`
- `src/main/java/com/example/snspublisher/service/AsyncFifoSnsPublisher.java`

**Components:** SnsEvent record, publishEvents method

## Acceptance Criteria

- [ ] Null messageGroupId throws meaningful exception before processing
- [ ] Null messageDeduplicationId throws meaningful exception
- [ ] Field length constraints match AWS limits
- [ ] Unit tests verify validation behavior
- [ ] Remove unused `sequenceNumber` field

## Work Log

| Date | Action | Learnings |
|------|--------|-----------|
| 2026-01-20 | Created from code review | Identified by security-sentinel |

## Resources

- [AWS SNS FIFO Message Groups](https://docs.aws.amazon.com/sns/latest/dg/fifo-message-grouping.html)
- [AWS SNS Message Deduplication](https://docs.aws.amazon.com/sns/latest/dg/fifo-message-dedup.html)
