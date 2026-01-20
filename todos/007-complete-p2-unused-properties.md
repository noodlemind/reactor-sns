---
status: pending
priority: p2
issue_id: "007"
tags: [code-review, simplicity, configuration]
dependencies: []
---

# Unused Configuration Properties

## Problem Statement

`SnsPublisherProperties` defines `maxBatchSize` and `maxPayloadSizeBytes` as configurable properties, but `AsyncFifoSnsPublisher` uses hardcoded constants instead. This creates confusion about what's actually configurable.

**Why it matters:**
- YAGNI violation - these are AWS hard limits, not configurable
- Misleading API - users may think they can change these
- Dead code maintenance burden

## Findings

**Location 1:** `src/main/java/com/example/snspublisher/config/SnsPublisherProperties.java` (Lines 33-42, 76-90)

```java
// Defined but never used
private int maxBatchSize = 10;
private int maxPayloadSizeBytes = 256 * 1024;
// Plus getters and setters...
```

**Location 2:** `src/main/java/com/example/snspublisher/service/AsyncFifoSnsPublisher.java` (Lines 26-27)

```java
// Used instead
private static final int MAX_BATCH_SIZE = 10;
private static final int MAX_PAYLOAD_SIZE_BYTES = 256 * 1024;
```

**Additionally unused:** `SnsEvent.sequenceNumber` field is defined but never read.

**Evidence:**
- Agent: code-simplicity-reviewer, pattern-recognition-specialist
- Grep shows no usage of `getMaxBatchSize()` or `getMaxPayloadSizeBytes()`
- These are AWS SNS hard limits - making them configurable implies they can change

## Proposed Solutions

### Option 1: Remove Unused Properties (Recommended)

Delete from `SnsPublisherProperties`:
- `maxBatchSize` field and getter/setter
- `maxPayloadSizeBytes` field and getter/setter

Delete from `SnsEvent`:
- `sequenceNumber` field

**Pros:** Clean API, no confusion, less code
**Cons:** Breaking change if anyone references these properties
**Effort:** Small
**Risk:** Low

### Option 2: Wire Properties Into Publisher

Actually use the properties in `AsyncFifoSnsPublisher`.

**Pros:** Properties become meaningful
**Cons:** Allows invalid configuration (>10 batch or >256KB)
**Effort:** Medium
**Risk:** Medium - could cause AWS rejections

### Option 3: Document as Fixed Limits

Keep properties but add validation that enforces AWS limits.

**Pros:** Documents the limits in code
**Cons:** Unnecessary complexity
**Effort:** Medium
**Risk:** Low

## Recommended Action

_To be filled during triage_

## Technical Details

**Affected files:**
- `src/main/java/com/example/snspublisher/config/SnsPublisherProperties.java`
- `src/main/java/com/example/snspublisher/model/SnsEvent.java`

**Components:** SnsPublisherProperties, SnsEvent

**Estimated LOC reduction:** ~20 lines

## Acceptance Criteria

- [ ] `maxBatchSize` property removed or wired into publisher
- [ ] `maxPayloadSizeBytes` property removed or wired into publisher
- [ ] `sequenceNumber` field removed from SnsEvent
- [ ] Tests updated to reflect changes
- [ ] No unused code in properties class

## Work Log

| Date | Action | Learnings |
|------|--------|-----------|
| 2026-01-20 | Created from code review | Identified by code-simplicity-reviewer |

## Resources

- [AWS SNS Quotas](https://docs.aws.amazon.com/general/latest/gr/sns.html)
