---
status: pending
priority: p3
issue_id: "011"
tags: [code-review, simplicity, cleanup]
dependencies: []
---

# Commented-Out Code Should Be Removed

## Problem Statement

The codebase contains commented-out code that adds noise and suggests incomplete decision-making. Dead code should be deleted - it's preserved in git history if ever needed.

**Why it matters:**
- Code noise reduces readability
- Suggests unfinished work
- May confuse future maintainers

## Findings

**Location:** `src/main/java/com/example/snspublisher/service/AsyncFifoSnsPublisher.java` (Lines 63-67)

```java
// Backpressure: If downstream (SNS) is slower than upstream (DB), buffer up to
// a limit (e.g. 10k items)
// preventing OOM. If buffer fills, we can Drop, Error, or Block (depending on
// business need).
// Here we buffer, assuming standard Reactor backpressure propagation works with
// the upstream source.
// Increased to 1M to withstand high throughput load testing bursts
// .onBackpressureBuffer(1000000)
```

**Evidence:**
- Agent: code-simplicity-reviewer
- 8 lines of commented code with extensive explanation
- If the feature is needed, implement it; if not, delete it

## Proposed Solutions

### Option 1: Delete Commented Code (Recommended)

Remove lines 63-67 entirely. If backpressure is needed (see #006), implement it properly.

**Pros:** Clean code, clear intent
**Cons:** None - git history preserves it
**Effort:** Trivial
**Risk:** None

### Option 2: Implement the Backpressure

Uncomment and make it configurable (see issue #006).

**Pros:** Actually addresses the underlying concern
**Cons:** More work
**Effort:** Small
**Risk:** Low

## Recommended Action

_To be filled during triage_

## Technical Details

**Affected files:**
- `src/main/java/com/example/snspublisher/service/AsyncFifoSnsPublisher.java`

**LOC reduction:** 8 lines

## Acceptance Criteria

- [ ] No commented-out code in production files
- [ ] Backpressure concern addressed in separate issue (#006)

## Work Log

| Date | Action | Learnings |
|------|--------|-----------|
| 2026-01-20 | Created from code review | Identified by code-simplicity-reviewer |

## Resources

- Related: #006-pending-p2-missing-backpressure-at-stream-entry.md
