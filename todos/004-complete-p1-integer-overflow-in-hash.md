---
status: pending
priority: p1
issue_id: "004"
tags: [code-review, data-integrity, bug, critical]
dependencies: []
---

# Integer Overflow Bug in Partition Hash Calculation

## Problem Statement

The partition calculation uses `Math.abs(hashCode)` which returns a negative value for `Integer.MIN_VALUE`, causing potential negative partition indices that could throw exceptions or cause undefined behavior.

**Why it matters:** This is a subtle but serious bug that can cause runtime failures with certain messageGroupId values.

## Findings

**Location:** `src/main/java/com/example/snspublisher/service/AsyncFifoSnsPublisher.java` (Line 70)

```java
.groupBy(event -> Math.abs(event.messageGroupId().hashCode()) % partitionCount)
```

**The Bug:**
```java
Math.abs(Integer.MIN_VALUE) // Returns Integer.MIN_VALUE (negative!)
Integer.MIN_VALUE % 256     // Returns -128 (negative partition index)
```

**Evidence:**
- Agent: data-integrity-guardian
- `Integer.MIN_VALUE` is `-2147483648`
- `Math.abs(-2147483648)` returns `-2147483648` (overflow)
- Negative partition index may cause `groupBy` to behave unexpectedly

**How to trigger:** Any `messageGroupId` whose `hashCode()` equals `Integer.MIN_VALUE` (e.g., certain strings like "polygenelubricants").

## Proposed Solutions

### Option 1: Bitwise AND with MAX_VALUE (Recommended)

```java
.groupBy(event -> (event.messageGroupId().hashCode() & Integer.MAX_VALUE) % partitionCount)
```

**Pros:** Simple, guaranteed non-negative, efficient
**Cons:** None
**Effort:** Trivial (1 line change)
**Risk:** None

### Option 2: Use Math.floorMod

```java
.groupBy(event -> Math.floorMod(event.messageGroupId().hashCode(), partitionCount))
```

**Pros:** Semantically clearer for modulo operation
**Cons:** Slightly less familiar to some developers
**Effort:** Trivial
**Risk:** None

### Option 3: Murmur3 Hash

Use a proper hash function designed for distribution.

```java
.groupBy(event -> Hashing.murmur3_32().hashString(event.messageGroupId(), UTF_8).asInt() & Integer.MAX_VALUE % partitionCount)
```

**Pros:** Better distribution
**Cons:** Adds Guava dependency
**Effort:** Small
**Risk:** Low

## Recommended Action

_To be filled during triage_

## Technical Details

**Affected files:**
- `src/main/java/com/example/snspublisher/service/AsyncFifoSnsPublisher.java`

**Components:** publishEvents method, groupBy operator

## Acceptance Criteria

- [ ] Partition calculation always returns non-negative value
- [ ] Unit test with messageGroupId that hashes to MIN_VALUE
- [ ] No change in behavior for normal hash values

## Work Log

| Date | Action | Learnings |
|------|--------|-----------|
| 2026-01-20 | Created from code review | Identified by data-integrity-guardian |

## Resources

- [Java Math.abs documentation](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Math.html#abs(int))
- [String with MIN_VALUE hashCode](https://stackoverflow.com/questions/9276475/which-string-hashes-to-integer-min-value)
