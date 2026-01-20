---
status: pending
priority: p1
issue_id: "002"
tags: [code-review, resource-management, critical]
dependencies: []
---

# Scheduler Resource Leak - No Shutdown Hook

## Problem Statement

The `AsyncFifoSnsPublisher` creates a `BoundedElasticScheduler` with up to 500 threads and 10,000 queued tasks, but never disposes it. This causes thread leaks on application shutdown and potential resource exhaustion if multiple instances are created.

**Why it matters:** Thread leaks in long-running applications, JVM shutdown delays, and potential OOM if the library is incorrectly instantiated multiple times.

## Findings

**Location:** `src/main/java/com/example/snspublisher/service/AsyncFifoSnsPublisher.java` (Line 45)

```java
this.ioScheduler = Schedulers.newBoundedElastic(500, 10000, "sns-publisher-io");
// Never disposed - scheduler lives forever
```

**Evidence:**
- Agent: security-sentinel, performance-oracle, pattern-recognition-specialist, architecture-strategist
- Class does not implement `DisposableBean` or `AutoCloseable`
- No `@PreDestroy` method exists
- Each instance reserves 500 daemon threads minimum

**Impact:**
- 500 threads x ~1MB stack = 500MB memory reserved per instance
- Threads never cleaned up on shutdown
- Test scenarios may leak threads between tests

## Proposed Solutions

### Option 1: Implement DisposableBean (Recommended)
Add Spring lifecycle management to dispose scheduler on shutdown.

```java
public class AsyncFifoSnsPublisher implements DisposableBean {
    @Override
    public void destroy() {
        ioScheduler.dispose();
    }
}
```

**Pros:** Integrates with Spring lifecycle, automatic cleanup
**Cons:** Couples to Spring interface
**Effort:** Small
**Risk:** Low

### Option 2: Inject Scheduler as Bean
Create the scheduler as a Spring bean and inject it.

```java
@Bean
@ConditionalOnMissingBean(name = "snsPublisherScheduler")
public Scheduler snsPublisherScheduler() {
    return Schedulers.newBoundedElastic(500, 10000, "sns-publisher-io");
}
```

**Pros:** More flexible, allows customization, testable
**Cons:** More code, changes API
**Effort:** Medium
**Risk:** Low

### Option 3: Use Shared Scheduler
Use `Schedulers.boundedElastic()` (Reactor's shared instance) instead of creating a dedicated one.

**Pros:** No resource management needed, simpler
**Cons:** Less isolation, may affect other Reactor operations
**Effort:** Small
**Risk:** Medium - potential interference with other reactive code

## Recommended Action

_To be filled during triage_

## Technical Details

**Affected files:**
- `src/main/java/com/example/snspublisher/service/AsyncFifoSnsPublisher.java`
- `src/main/java/com/example/snspublisher/config/SnsPublisherAutoConfiguration.java` (if Option 2)

**Components:** AsyncFifoSnsPublisher constructor and lifecycle

## Acceptance Criteria

- [ ] Scheduler is disposed when application context closes
- [ ] No thread leaks observed after running tests
- [ ] Integration test verifies clean shutdown
- [ ] JVM shutdown completes within reasonable time

## Work Log

| Date | Action | Learnings |
|------|--------|-----------|
| 2026-01-20 | Created from code review | Identified by all review agents |

## Resources

- [Reactor Scheduler documentation](https://projectreactor.io/docs/core/release/reference/#schedulers)
- [Spring DisposableBean](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/beans/factory/DisposableBean.html)
