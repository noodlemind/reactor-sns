package io.clype.reactorsns.ratelimit;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.util.concurrent.RateLimiter;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

/**
 * Adaptive token bucket rate limiter for SNS FIFO publishing to prevent throttling.
 *
 * <p>Enforces rate limits at two levels:</p>
 * <ul>
 *   <li><b>Topic level:</b> Limits total API requests per second (default: 2500, ~83% of AWS 3,000 limit)</li>
 *   <li><b>Group level:</b> Limits messages per second per messageGroupId (default: 250, ~83% of AWS 300 limit)</li>
 * </ul>
 *
 * <p><b>Adaptive Behavior:</b> When throttling is detected via {@link #onThrottle(String)}, the rate
 * limiter automatically reduces its rate by 50% (minimum 10% of configured rate). After successful
 * requests, the rate gradually recovers.</p>
 *
 * <p><b>Memory Safety:</b> Per-group limiters use an LRU cache with bounded size (10,000 entries)
 * and automatic expiration (5 minutes after last access).</p>
 *
 * <p><b>Thread Safety:</b> This class is thread-safe. The underlying Guava RateLimiter and Cache
 * are both thread-safe.</p>
 */
public class SnsRateLimiter implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(SnsRateLimiter.class);

    private static final int MAX_GROUP_LIMITERS = 10_000;
    private static final Duration GROUP_LIMITER_EXPIRY = Duration.ofMinutes(5);
    private static final double MIN_RATE_FACTOR = 0.1;  // Don't go below 10% of configured rate
    private static final double THROTTLE_REDUCTION_FACTOR = 0.5;  // Reduce by 50% on throttle
    private static final double RECOVERY_INCREASE_FACTOR = 1.1;  // Increase by 10% on success

    private final RateLimiter topicRateLimiter;
    private final Cache<String, RateLimiter> groupRateLimiters;
    private final int requestsPerSecond;
    private final int messagesPerGroupPerSecond;
    private final Scheduler blockingScheduler;
    private final boolean enabled;
    private final Duration warmupPeriod;
    private final AtomicInteger consecutiveSuccesses = new AtomicInteger(0);
    private final AtomicReference<Double> currentRateFactor = new AtomicReference<>(1.0);
    private final Set<String> throttledGroups = ConcurrentHashMap.newKeySet();

    /**
     * Creates a disabled rate limiter that passes through all requests immediately.
     *
     * @return a no-op rate limiter
     */
    public static SnsRateLimiter disabled() {
        return new SnsRateLimiter(false, 0, 0, Duration.ZERO, null);
    }

    /**
     * Creates an enabled rate limiter with the specified configuration.
     *
     * @param requestsPerSecond          topic-level rate limit (API calls per second)
     * @param messagesPerGroupPerSecond  group-level rate limit (messages per second per messageGroupId)
     * @param warmupPeriod               period to gradually ramp up to full rate (zero for instant)
     * @param blockingScheduler          scheduler for blocking acquire operations
     */
    public SnsRateLimiter(int requestsPerSecond, int messagesPerGroupPerSecond,
                          Duration warmupPeriod, Scheduler blockingScheduler) {
        this(true, requestsPerSecond, messagesPerGroupPerSecond, warmupPeriod, blockingScheduler);
    }

    private SnsRateLimiter(boolean enabled, int requestsPerSecond, int messagesPerGroupPerSecond,
                           Duration warmupPeriod, Scheduler blockingScheduler) {
        this.enabled = enabled;
        this.requestsPerSecond = requestsPerSecond;
        this.messagesPerGroupPerSecond = messagesPerGroupPerSecond;
        this.warmupPeriod = warmupPeriod;
        this.blockingScheduler = blockingScheduler;

        if (enabled) {
            if (requestsPerSecond <= 0) {
                throw new IllegalArgumentException("requestsPerSecond must be positive, got: " + requestsPerSecond);
            }
            if (messagesPerGroupPerSecond <= 0) {
                throw new IllegalArgumentException("messagesPerGroupPerSecond must be positive, got: " + messagesPerGroupPerSecond);
            }
            if (warmupPeriod == null) {
                throw new IllegalArgumentException("warmupPeriod cannot be null");
            }
            if (blockingScheduler == null) {
                throw new IllegalArgumentException("blockingScheduler cannot be null when rate limiting is enabled");
            }

            this.topicRateLimiter = createRateLimiter(requestsPerSecond, warmupPeriod);
            this.groupRateLimiters = CacheBuilder.newBuilder()
                    .maximumSize(MAX_GROUP_LIMITERS)
                    .expireAfterAccess(GROUP_LIMITER_EXPIRY.toMillis(), TimeUnit.MILLISECONDS)
                    .build();
        } else {
            this.topicRateLimiter = null;
            this.groupRateLimiters = null;
        }
    }

    /**
     * Acquires permits for publishing a batch of messages.
     *
     * <p>Blocks until permits are available, then returns. The returned Mono completes
     * immediately when rate limiting is disabled.</p>
     *
     * @param messageGroupId the message group ID for group-level rate limiting
     * @param messageCount   the number of messages in the batch
     * @return a Mono that completes when permits are acquired
     */
    public Mono<Void> acquirePermit(String messageGroupId, int messageCount) {
        if (!enabled) {
            return Mono.empty();
        }

        return Mono.fromRunnable(() -> {
            // 1 permit per API call at topic level
            topicRateLimiter.acquire();

            // 1 permit per message at group level
            RateLimiter groupLimiter = getOrCreateGroupLimiter(messageGroupId);
            groupLimiter.acquire(messageCount);
        }).subscribeOn(blockingScheduler).then();
    }

    private RateLimiter getOrCreateGroupLimiter(String messageGroupId) {
        try {
            return groupRateLimiters.get(messageGroupId, () ->
                    createRateLimiter(messagesPerGroupPerSecond, warmupPeriod));
        } catch (java.util.concurrent.ExecutionException e) {
            // Should never happen since our loader doesn't throw checked exceptions
            throw new IllegalStateException("Failed to create rate limiter for group: " + messageGroupId, e);
        }
    }

    /**
     * Called when a throttling error is received from AWS.
     *
     * <p>Reduces the topic-level rate by 50% (down to a minimum of 10% of the configured rate)
     * and optionally reduces the group-level rate for the affected messageGroupId.</p>
     *
     * @param messageGroupId the message group that was throttled (may be null for topic-level throttling)
     */
    public void onThrottle(String messageGroupId) {
        if (!enabled) {
            return;
        }

        consecutiveSuccesses.set(0);

        // Reduce topic rate atomically using compareAndSet loop
        double oldFactor;
        double newFactor;
        do {
            oldFactor = currentRateFactor.get();
            newFactor = Math.max(oldFactor * THROTTLE_REDUCTION_FACTOR, MIN_RATE_FACTOR);
            if (newFactor >= oldFactor) {
                // Already at or below the new factor, no update needed
                break;
            }
        } while (!currentRateFactor.compareAndSet(oldFactor, newFactor));

        if (newFactor < oldFactor) {
            double newRate = requestsPerSecond * newFactor;
            topicRateLimiter.setRate(newRate);
            log.warn("Throttling detected - reducing topic rate to {}/sec ({}% of configured)",
                    Math.round(newRate * 10) / 10.0, Math.round(newFactor * 100));
        }

        // Also reduce group rate if specified
        if (messageGroupId != null) {
            throttledGroups.add(messageGroupId);
            RateLimiter groupLimiter = groupRateLimiters.getIfPresent(messageGroupId);
            if (groupLimiter != null) {
                double currentGroupRate = groupLimiter.getRate();
                double minGroupRate = messagesPerGroupPerSecond * MIN_RATE_FACTOR;
                double newGroupRate = Math.max(currentGroupRate * THROTTLE_REDUCTION_FACTOR, minGroupRate);
                if (newGroupRate < currentGroupRate) {
                    groupLimiter.setRate(newGroupRate);
                    log.debug("Reducing rate for group '{}' to {}/sec", messageGroupId, Math.round(newGroupRate * 10) / 10.0);
                }
            }
        }
    }

    /**
     * Called when a batch is successfully published.
     *
     * <p>After enough consecutive successes, gradually recovers the rate toward the configured maximum.</p>
     */
    public void onSuccess() {
        if (!enabled || currentRateFactor.get() >= 1.0) {
            return;
        }

        // Recover after 10 consecutive successes
        if (consecutiveSuccesses.incrementAndGet() >= 10) {
            consecutiveSuccesses.set(0);

            // Increase rate atomically using compareAndSet loop
            double oldFactor;
            double newFactor;
            do {
                oldFactor = currentRateFactor.get();
                newFactor = Math.min(oldFactor * RECOVERY_INCREASE_FACTOR, 1.0);
                if (newFactor <= oldFactor) {
                    // Already at or above the new factor, no update needed
                    break;
                }
            } while (!currentRateFactor.compareAndSet(oldFactor, newFactor));

            if (newFactor > oldFactor) {
                double newRate = requestsPerSecond * newFactor;
                topicRateLimiter.setRate(newRate);
                log.info("Recovering topic rate to {}/sec ({}% of configured)",
                        Math.round(newRate * 10) / 10.0, Math.round(newFactor * 100));
            }

            // Also recover throttled groups
            if (!throttledGroups.isEmpty()) {
                for (String groupId : throttledGroups) {
                    RateLimiter groupLimiter = groupRateLimiters.getIfPresent(groupId);
                    if (groupLimiter != null) {
                        double currentRate = groupLimiter.getRate();
                        double targetRate = messagesPerGroupPerSecond * currentRateFactor.get();
                        if (currentRate < targetRate) {
                            double newGroupRate = Math.min(currentRate * RECOVERY_INCREASE_FACTOR, targetRate);
                            groupLimiter.setRate(newGroupRate);
                            log.debug("Recovering rate for group '{}' to {}/sec", groupId, Math.round(newGroupRate * 10) / 10.0);
                        }
                        if (currentRate >= targetRate * 0.99) {
                            throttledGroups.remove(groupId);
                        }
                    } else {
                        throttledGroups.remove(groupId);
                    }
                }
            }
        }
    }

    /**
     * Returns the current rate factor (1.0 = full rate, lower = throttled).
     *
     * @return current rate factor between MIN_RATE_FACTOR and 1.0
     */
    public double getCurrentRateFactor() {
        return currentRateFactor.get();
    }

    /**
     * Returns whether rate limiting is enabled.
     *
     * @return true if rate limiting is active
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Disposes the blocking scheduler when the bean is destroyed.
     */
    @Override
    public void destroy() {
        if (enabled && blockingScheduler != null) {
            blockingScheduler.dispose();
            log.info("Rate limiter scheduler disposed");
        }
    }

    private static RateLimiter createRateLimiter(int permitsPerSecond, Duration warmupPeriod) {
        if (warmupPeriod.isZero()) {
            return RateLimiter.create(permitsPerSecond);
        }
        return RateLimiter.create(permitsPerSecond, warmupPeriod.toMillis(), TimeUnit.MILLISECONDS);
    }
}
