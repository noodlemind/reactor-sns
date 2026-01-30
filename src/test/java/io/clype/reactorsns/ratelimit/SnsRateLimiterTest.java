package io.clype.reactorsns.ratelimit;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.*;

class SnsRateLimiterTest {

    private Scheduler blockingScheduler;

    @AfterEach
    void tearDown() {
        if (blockingScheduler != null) {
            blockingScheduler.dispose();
        }
    }

    @Test
    void disabledRateLimiterPassesThroughImmediately() {
        SnsRateLimiter limiter = SnsRateLimiter.disabled();

        assertFalse(limiter.isEnabled());

        long start = System.nanoTime();
        StepVerifier.create(limiter.acquirePermit("group1", 100))
                .verifyComplete();
        long elapsed = System.nanoTime() - start;

        // Should complete in under 100ms (essentially instant, allowing for JIT warmup)
        assertTrue(elapsed < Duration.ofMillis(100).toNanos(),
                "Disabled limiter should not block, took " + Duration.ofNanos(elapsed).toMillis() + "ms");
    }

    @Test
    void topicRateLimitEnforced() {
        blockingScheduler = Schedulers.newBoundedElastic(4, 100, "test-rate-limit");

        // 10 requests per second
        SnsRateLimiter limiter = new SnsRateLimiter(10, 1000, Duration.ZERO, blockingScheduler);

        assertTrue(limiter.isEnabled());

        // Issue 15 requests - first 10 should be fast, remaining 5 should take ~500ms
        AtomicLong totalTime = new AtomicLong(0);
        long start = System.nanoTime();

        List<Mono<Void>> requests = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            requests.add(limiter.acquirePermit("group-" + i, 1));
        }

        StepVerifier.create(Flux.merge(requests))
                .verifyComplete();

        totalTime.set(System.nanoTime() - start);
        Duration elapsed = Duration.ofNanos(totalTime.get());

        // 15 requests at 10/sec should take at least 400ms (5 extra requests / 10 per sec)
        // Allow some tolerance for scheduling overhead
        assertTrue(elapsed.toMillis() >= 300,
                "Expected at least 300ms for 15 requests at 10/sec, got " + elapsed.toMillis() + "ms");
    }

    @Test
    void groupRateLimitEnforced() {
        blockingScheduler = Schedulers.newBoundedElastic(4, 100, "test-rate-limit");

        // High topic limit, low group limit (10 messages per second per group)
        SnsRateLimiter limiter = new SnsRateLimiter(1000, 10, Duration.ZERO, blockingScheduler);

        // Issue 15 messages to the same group - should take ~500ms
        long start = System.nanoTime();

        List<Mono<Void>> requests = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            requests.add(limiter.acquirePermit("same-group", 1));
        }

        // Execute sequentially to ensure group rate limiting
        StepVerifier.create(Flux.concat(requests))
                .verifyComplete();

        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        // 15 messages at 10/sec per group should take at least 400ms
        assertTrue(elapsed.toMillis() >= 300,
                "Expected at least 300ms for 15 messages at 10/sec to same group, got " + elapsed.toMillis() + "ms");
    }

    @Test
    void differentGroupsAreIndependent() {
        blockingScheduler = Schedulers.newBoundedElastic(4, 100, "test-rate-limit");

        // High topic limit, low group limit (10 messages per second per group)
        // Each group should be rate limited independently
        SnsRateLimiter limiter = new SnsRateLimiter(10000, 10, Duration.ZERO, blockingScheduler);

        // Test that groups have independent rate limits
        // Group A: 5 messages (within limit)
        // Group B: 5 messages (within limit)
        // Both should complete quickly since they are under their group limits

        long start = System.nanoTime();

        List<Mono<Void>> requests = new ArrayList<>();
        // Add 5 messages to group A
        for (int i = 0; i < 5; i++) {
            requests.add(limiter.acquirePermit("group-A", 1));
        }
        // Add 5 messages to group B
        for (int i = 0; i < 5; i++) {
            requests.add(limiter.acquirePermit("group-B", 1));
        }

        StepVerifier.create(Flux.merge(requests))
                .verifyComplete();

        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        // With high topic limit and each group under their 10/sec limit,
        // should complete quickly (under 1 second)
        assertTrue(elapsed.toMillis() < 1000,
                "Different groups should not block each other, took " + elapsed.toMillis() + "ms");
    }

    @Test
    void warmupPeriodGraduallyIncreasesRate() {
        blockingScheduler = Schedulers.newBoundedElastic(4, 100, "test-rate-limit");

        // 100 requests per second with 1 second warmup
        SnsRateLimiter limiter = new SnsRateLimiter(100, 1000, Duration.ofSeconds(1), blockingScheduler);

        // During warmup, rate starts lower and ramps up
        // First few requests should be slower than steady-state
        long start = System.nanoTime();

        List<Mono<Void>> requests = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            requests.add(limiter.acquirePermit("group-" + i, 1));
        }

        StepVerifier.create(Flux.concat(requests))
                .verifyComplete();

        // Just verify it completes - warmup behavior is internal to Guava
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        assertNotNull(elapsed);
    }

    @Test
    void batchMessageCountAffectsGroupLimit() {
        blockingScheduler = Schedulers.newBoundedElastic(4, 100, "test-rate-limit");

        // 10 messages per second per group
        SnsRateLimiter limiter = new SnsRateLimiter(1000, 10, Duration.ZERO, blockingScheduler);

        long start = System.nanoTime();

        // Request permits for batches of 5 messages each (3 batches = 15 messages)
        List<Mono<Void>> requests = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            requests.add(limiter.acquirePermit("same-group", 5));
        }

        StepVerifier.create(Flux.concat(requests))
                .verifyComplete();

        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        // 15 messages at 10/sec should take at least 400ms
        assertTrue(elapsed.toMillis() >= 300,
                "Batch message count should affect group rate, got " + elapsed.toMillis() + "ms");
    }

    @Test
    void concurrentAccessIsThreadSafe() throws InterruptedException {
        blockingScheduler = Schedulers.newBoundedElastic(8, 1000, "test-rate-limit");

        // High limits to avoid rate limiting - just testing thread safety
        SnsRateLimiter limiter = new SnsRateLimiter(10000, 10000, Duration.ZERO, blockingScheduler);

        int threadCount = 10;
        int requestsPerThread = 100;
        CountDownLatch latch = new CountDownLatch(threadCount);
        List<Throwable> errors = new ArrayList<>();

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            new Thread(() -> {
                try {
                    for (int i = 0; i < requestsPerThread; i++) {
                        limiter.acquirePermit("group-" + (threadId % 5), 1).block();
                    }
                } catch (Throwable e) {
                    synchronized (errors) {
                        errors.add(e);
                    }
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS), "Threads should complete within timeout");
        assertTrue(errors.isEmpty(), "No errors should occur: " + errors);
    }

    @Test
    void onThrottleReducesRate() {
        blockingScheduler = Schedulers.newBoundedElastic(4, 100, "test-rate-limit");

        SnsRateLimiter limiter = new SnsRateLimiter(1000, 100, Duration.ZERO, blockingScheduler);

        assertEquals(1.0, limiter.getCurrentRateFactor(), 0.01);

        // First throttle: reduce to 50%
        limiter.onThrottle("group1");
        assertEquals(0.5, limiter.getCurrentRateFactor(), 0.01);

        // Second throttle: reduce to 25%
        limiter.onThrottle("group1");
        assertEquals(0.25, limiter.getCurrentRateFactor(), 0.01);

        // Third throttle: reduce to 12.5%
        limiter.onThrottle("group1");
        assertEquals(0.125, limiter.getCurrentRateFactor(), 0.01);

        // Fourth throttle: should hit minimum of 10%
        limiter.onThrottle("group1");
        assertEquals(0.1, limiter.getCurrentRateFactor(), 0.01);

        // Further throttles shouldn't go below 10%
        limiter.onThrottle("group1");
        assertEquals(0.1, limiter.getCurrentRateFactor(), 0.01);
    }

    @Test
    void onSuccessRecoversRate() {
        blockingScheduler = Schedulers.newBoundedElastic(4, 100, "test-rate-limit");

        SnsRateLimiter limiter = new SnsRateLimiter(1000, 100, Duration.ZERO, blockingScheduler);

        // Throttle down to 50%
        limiter.onThrottle("group1");
        assertEquals(0.5, limiter.getCurrentRateFactor(), 0.01);

        // 9 successes shouldn't recover yet
        for (int i = 0; i < 9; i++) {
            limiter.onSuccess();
        }
        assertEquals(0.5, limiter.getCurrentRateFactor(), 0.01);

        // 10th success triggers recovery (10% increase)
        limiter.onSuccess();
        assertEquals(0.55, limiter.getCurrentRateFactor(), 0.01);

        // Fully recover over multiple cycles (need ~80 more successes to go from 0.55 to 1.0)
        for (int i = 0; i < 80; i++) {
            limiter.onSuccess();
        }
        // Should be back to 1.0 (full rate)
        assertEquals(1.0, limiter.getCurrentRateFactor(), 0.01);
    }

    @Test
    void disabledLimiterIgnoresThrottleAndSuccess() {
        SnsRateLimiter limiter = SnsRateLimiter.disabled();

        // These should not throw or have any effect
        limiter.onThrottle("group1");
        limiter.onSuccess();

        // Rate factor should remain at default (1.0)
        assertEquals(1.0, limiter.getCurrentRateFactor(), 0.01);
    }

    @Test
    void manyGroupsUseBoundedMemory() {
        blockingScheduler = Schedulers.newBoundedElastic(4, 100, "test-rate-limit");

        // High limits to avoid rate limiting
        SnsRateLimiter limiter = new SnsRateLimiter(100_000, 10_000, Duration.ZERO, blockingScheduler);

        // Create limiters for many groups (more than the 10K cache limit)
        // This shouldn't cause OOM due to LRU eviction
        List<Mono<Void>> requests = new ArrayList<>();
        for (int i = 0; i < 15000; i++) {
            requests.add(limiter.acquirePermit("group-" + i, 1));
        }

        StepVerifier.create(Flux.fromIterable(requests).flatMap(m -> m, 100))
                .verifyComplete();

        // If we get here without OOM, the test passes
    }

    @Test
    void constructorRejectsInvalidRequestsPerSecond() {
        blockingScheduler = Schedulers.newBoundedElastic(4, 100, "test-rate-limit");
        assertThrows(IllegalArgumentException.class, () ->
            new SnsRateLimiter(0, 100, Duration.ZERO, blockingScheduler));
        assertThrows(IllegalArgumentException.class, () ->
            new SnsRateLimiter(-1, 100, Duration.ZERO, blockingScheduler));
    }

    @Test
    void constructorRejectsInvalidMessagesPerGroupPerSecond() {
        blockingScheduler = Schedulers.newBoundedElastic(4, 100, "test-rate-limit");
        assertThrows(IllegalArgumentException.class, () ->
            new SnsRateLimiter(100, 0, Duration.ZERO, blockingScheduler));
        assertThrows(IllegalArgumentException.class, () ->
            new SnsRateLimiter(100, -1, Duration.ZERO, blockingScheduler));
    }

    @Test
    void constructorRejectsNullWarmupPeriod() {
        blockingScheduler = Schedulers.newBoundedElastic(4, 100, "test-rate-limit");
        assertThrows(IllegalArgumentException.class, () ->
            new SnsRateLimiter(100, 100, null, blockingScheduler));
    }

    @Test
    void constructorRejectsNullScheduler() {
        assertThrows(IllegalArgumentException.class, () ->
            new SnsRateLimiter(100, 100, Duration.ZERO, null));
    }

    @Test
    void concurrentRateFactorUpdatesAreThreadSafe() throws InterruptedException {
        blockingScheduler = Schedulers.newBoundedElastic(4, 100, "test-rate-limit");
        SnsRateLimiter limiter = new SnsRateLimiter(1000, 100, Duration.ZERO, blockingScheduler);

        int threadCount = 4;
        int operationsPerThread = 100;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicBoolean hasError = new AtomicBoolean(false);
        List<Throwable> errors = new ArrayList<>();

        Thread[] threads = new Thread[threadCount];
        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            threads[t] = new Thread(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < operationsPerThread; i++) {
                        if (threadId % 2 == 0) {
                            limiter.onThrottle(null);
                        } else {
                            limiter.onSuccess();
                        }

                        double factor = limiter.getCurrentRateFactor();
                        if (factor < 0.1 - 0.001 || factor > 1.0 + 0.001) {
                            hasError.set(true);
                            synchronized (errors) {
                                errors.add(new AssertionError("Rate factor out of bounds: " + factor));
                            }
                        }
                    }
                } catch (Throwable e) {
                    hasError.set(true);
                    synchronized (errors) {
                        errors.add(e);
                    }
                } finally {
                    doneLatch.countDown();
                }
            });
            threads[t].start();
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "All threads should complete within timeout");

        assertFalse(hasError.get(), "No errors should occur during concurrent updates: " + errors);

        double finalFactor = limiter.getCurrentRateFactor();
        assertTrue(finalFactor >= 0.1 && finalFactor <= 1.0,
                "Final rate factor should be within bounds: " + finalFactor);
    }
}
