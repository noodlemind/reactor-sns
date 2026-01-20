---
status: pending
priority: p3
issue_id: "012"
tags: [code-review, simplicity, cleanup]
dependencies: []
---

# Excessive Comments Explaining Obvious Code

## Problem Statement

Many comments in `AsyncFifoSnsPublisher.java` explain what the code does rather than why. The code is readable enough without them. Comments should add value, not noise.

**Why it matters:**
- Comments that restate code add maintenance burden
- They can become stale and misleading
- They obscure the important comments

## Findings

**Location:** `src/main/java/com/example/snspublisher/service/AsyncFifoSnsPublisher.java`

**Examples of redundant comments:**

Line 74: `// Buffer into chunks of 10 or flush every 10ms (configurable)`
- The code `.bufferTimeout(MAX_BATCH_SIZE, batchTimeout)` says this

Line 77: `// Process each batch in this partition sequentially to ensure FIFO order`
- `concatMap` is well-known for sequential processing

Lines 83-86:
```java
// After standard buffering, we must split batches that exceed payload size
// This is a safety valve. It's more efficient to check size during
// accumulation,
// but bufferTimeout is highly optimized for time.
```
- This is actually a good comment (explains why), but could be shorter

**Comments to keep:**
- Line 44-45: Explains why a dedicated scheduler is used
- The payload split safety valve comment (explains design decision)

**Evidence:**
- Agent: code-simplicity-reviewer
- ~15 lines of excessive comments identified

## Proposed Solutions

### Option 1: Trim to Essential Comments (Recommended)

Keep only:
- Class-level Javadoc on `publishEvents`
- Scheduler choice explanation
- Payload split safety valve explanation

Delete comments that restate what the code does.

**Pros:** Better signal-to-noise ratio
**Cons:** Less hand-holding for beginners
**Effort:** Small
**Risk:** None

### Option 2: Convert to Javadoc

Move explanations to method-level Javadoc.

**Pros:** Standard documentation format
**Cons:** May still be verbose
**Effort:** Small
**Risk:** None

## Recommended Action

_To be filled during triage_

## Technical Details

**Affected files:**
- `src/main/java/com/example/snspublisher/service/AsyncFifoSnsPublisher.java`

**Estimated LOC reduction:** ~15 lines

## Acceptance Criteria

- [ ] Comments explain "why" not "what"
- [ ] No comments that restate obvious code
- [ ] Important design decisions are still documented

## Work Log

| Date | Action | Learnings |
|------|--------|-----------|
| 2026-01-20 | Created from code review | Identified by code-simplicity-reviewer |

## Resources

- [Clean Code - Comments chapter](https://www.oreilly.com/library/view/clean-code-a/9780136083238/)
