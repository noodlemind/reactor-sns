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
| `sns.publisher.partition-count` | `256` | Parallel processing partitions |
| `sns.publisher.batch-timeout` | `10ms` | Max wait time before sending batch |

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

## Requirements

- Java 17+
- Spring Boot 3.x
- AWS SDK v2

## License

[Apache License 2.0](LICENSE)
