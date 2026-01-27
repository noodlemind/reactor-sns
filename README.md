# Reactor SNS

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Java](https://img.shields.io/badge/Java-17%2B-blue)](https://openjdk.org/)

High-throughput reactive Amazon SNS publisher for FIFO topics using Project Reactor.

## Features

- **High Throughput**: Parallel processing across configurable partitions with automatic batching
- **FIFO Ordering**: Preserves message ordering within message groups
- **Reactive**: Built on Project Reactor with full backpressure support
- **Production Ready**: AWS CRT HTTP client, retry with exponential backoff, proper resource cleanup
- **Spring Boot**: Auto-configuration with `@ConditionalOnMissingBean` for customization

## Installation

Add the dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>io.clype</groupId>
    <artifactId>reactor-sns</artifactId>
    <version>1.0.0</version>
</dependency>
```

## Quick Start

Configure in `application.yml`:

```yaml
sns:
  publisher:
    topic-arn: arn:aws:sns:us-east-1:123456789012:MyTopic.fifo
```

Inject and use:

```java
import io.clype.reactorsns.model.SnsEvent;
import io.clype.reactorsns.service.AsyncFifoSnsPublisher;

@Service
public class MyService {
    private final AsyncFifoSnsPublisher publisher;

    public MyService(AsyncFifoSnsPublisher publisher) {
        this.publisher = publisher;
    }

    public Mono<Void> publish(String groupId, String payload) {
        SnsEvent event = new SnsEvent(groupId, UUID.randomUUID().toString(), payload);
        return publisher.publishEvents(Flux.just(event)).then();
    }
}
```

## Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `sns.publisher.topic-arn` | Required | SNS FIFO topic ARN |
| `sns.publisher.region` | AWS default | AWS region |
| `sns.publisher.partition-count` | `256` | Parallel processing partitions (max: 4096) |
| `sns.publisher.batch-timeout` | `10ms` | Max wait time before sending batch |
| `sns.publisher.max-connections` | `100` | HTTP client connection pool size |
| `sns.publisher.api-call-timeout` | `30s` | Total timeout for API call including retries |
| `sns.publisher.api-call-attempt-timeout` | `10s` | Timeout for single API attempt |
| `sns.publisher.backpressure.buffer-size` | `10000` | Main buffer size (max: 1,000,000) |
| `sns.publisher.backpressure.partition-buffer-size` | `100` | Per-partition buffer (max: 10,000) |

## How It Works

```
Input Stream → Backpressure Buffer → Group by Message Group ID
                                              ↓
                                    Partition (hash % N)
                                              ↓
                              ┌───────────────┼───────────────┐
                              ↓               ↓               ↓
                         Partition 0    Partition 1    Partition N
                              ↓               ↓               ↓
                         Buffer/Batch   Buffer/Batch   Buffer/Batch
                              ↓               ↓               ↓
                         SNS Publish    SNS Publish    SNS Publish
```

Messages with the same `messageGroupId` always route to the same partition, ensuring FIFO ordering per group while enabling parallel processing across groups.

## SNS FIFO Topic Requirements

### High Throughput Mode

For optimal performance, configure your SNS FIFO topic with high throughput mode:

```bash
aws sns set-topic-attributes \
  --topic-arn arn:aws:sns:us-east-1:123456789012:MyTopic.fifo \
  --attribute-name FifoThroughputScope \
  --attribute-value messageGroup
```

**FifoThroughputScope options:**
- `topic` (default): 3,000 msg/s per topic, deduplication scope is topic-wide
- `messageGroup`: Scales with distinct message groups, deduplication scope is per-group

### Throughput Limits

| Limit | Value |
|-------|-------|
| Per message group | 300 msg/s |
| Per topic (standard mode) | 3,000 msg/s |
| Per topic (high throughput) | Scales with message groups |
| Per batch | 10 messages, 256 KB |

## Ordering Guarantees

### What This Library Guarantees

1. **Per-group FIFO**: Messages with the same `messageGroupId` are published in order
2. **Retry ordering**: Failed batches are retried before subsequent batches for the same group
3. **FIFO violation detection**: If SNS delivers messages out of order (partial batch failure), a non-retryable `FifoOrderingViolationException` is thrown

### Caller Responsibilities

1. **Input stream ordering**: Provide events in the correct order per `messageGroupId`
2. **Single writer per group**: If multiple application instances publish to the same `messageGroupId`, coordinate externally (e.g., shard by group ID across instances)
3. **Deduplication ID stability**: Use stable, deterministic `messageDeduplicationId` values (e.g., derived from database primary key) for safe retries

## Error Handling

### Retryable Errors (automatic retry with backoff)
- `Throttling` - Rate limit exceeded
- `ServiceUnavailable` - Transient service issue
- `InternalError` - AWS internal error
- Network timeouts and connection errors

### Non-Retryable Errors (fail immediately)
- `InvalidParameter` - Message validation failed
- `FifoOrderingViolationException` - FIFO order already violated at SNS level
- Any error with `senderFault=true`

### Handling Failures

```java
publisher.publishEvents(eventStream)
    .doOnError(FifoOrderingViolationException.class, e -> {
        // FIFO violation - message was delivered out of order
        // Alert operations, log for investigation
        log.error("FIFO violation for group {}: {}",
            e.getMessageGroupId(), e.getFailedEntries());
    })
    .doOnError(PartialBatchFailureException.class, e -> {
        // Batch partially failed after retries
        // Consider dead-letter queue for failed entries
        log.error("Partial failure: {} failed", e.getFailureCount());
    })
    .subscribe();
```

## Performance Tuning

### Warm-Up Recommendation

SNS may throttle sudden traffic increases. For batch jobs processing millions of events, consider ramping up gradually:

```java
// Option 1: Use limitRate to control throughput
publisher.publishEvents(events)
    .limitRate(100)  // Start with limited prefetch
    .subscribe();

// Option 2: Use delayElements for initial throttling
publisher.publishEvents(
    events.delayElements(Duration.ofMillis(10))  // ~100 events/sec initially
          .take(Duration.ofSeconds(30))          // Warm-up period
).subscribe();
```

### Partition Count Tuning

- **More partitions** = higher parallelism, more memory
- **Fewer partitions** = lower memory, potential head-of-line blocking
- Default of 256 works well for most workloads
- Increase if you have many distinct `messageGroupId` values and need higher throughput

## Requirements

- Java 17+
- Spring Boot 3.x
- AWS SDK v2

## OpenTelemetry Compatibility

If using OpenTelemetry Java agent with this library, you must enable Reactor context propagation in your application. See [Reactor Context Propagation](https://projectreactor.io/docs/core/release/reference/#context.propagation) and add `Hooks.enableAutomaticContextPropagation()` at startup.

## License

[Apache License 2.0](LICENSE)
