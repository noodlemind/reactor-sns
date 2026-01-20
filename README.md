# Async FIFO SNS Publisher

A high-throughput, asynchronous Amazon SNS publisher library for Spring Boot applications. This library ensures FIFO (First-In-First-Out) message ordering by leveraging SNS FIFO topics and provides efficient batching capabilities using Project Reactor.

## Features

- **High Throughput**: Uses parallel processing and batching to maximize SNS throughput.
- **FIFO Ordering**: Preserves message ordering within message groups using SNS FIFO topics.
- **Asynchronous**: Built on top of the AWS SDK v2 Async Client and Project Reactor.
- **Auto-Configuration**: seamless integration with Spring Boot.
- **Configurable**: Customizable partition counts, batch sizes, and timeouts.

## Installation

Add the following dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>io.clype</groupId>
    <artifactId>async-fifo-sns-publisher</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

## Configuration

Configure the library in your `application.yml` or `application.properties`:

```yaml
sns:
  publisher:
    topic-arn: arn:aws:sns:us-east-1:123456789012:MyTopic.fifo
    region: us-east-1             # Optional, defaults to AWS Default Region Provider Chain
    partition-count: 256          # Optional, default: 256
    batch-size: 10                # Optional, default: 10 (Max allowed by SNS)
    batch-timeout: 10ms           # Optional, default: 10ms
```

### Properties Reference

| Property | Default | Description |
|----------|---------|-------------|
| `sns.publisher.topic-arn` | **Required** | The ARN of the SNS FIFO topic. |
| `sns.publisher.region` | `null` | AWS Region (e.g., `us-east-1`). If not set, uses the default AWS provider chain. |
| `sns.publisher.partition-count` | `256` | Logical partitions for parallel processing. Higher values increase parallelism. |
| `sns.publisher.batch-size` | `10` | Maximum number of messages to send in a single `PublishBatch` request. |
| `sns.publisher.batch-timeout` | `10ms` | Maximum time to wait for a batch to fill before sending. |

## Usage

Inject `AsyncFifoSnsPublisher` into your service and publish events:

```java
import com.example.snspublisher.service.AsyncFifoSnsPublisher;
import com.example.snspublisher.model.SnsEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
public class MyMessagingService {

    private final AsyncFifoSnsPublisher publisher;

    public MyMessagingService(AsyncFifoSnsPublisher publisher) {
        this.publisher = publisher;
    }

    public void sendMessages() {
        SnsEvent event1 = new SnsEvent("group-id-1", "dedup-id-1", "payload-1");
        SnsEvent event2 = new SnsEvent("group-id-1", "dedup-id-2", "payload-2");

        publisher.publishEvents(Flux.just(event1, event2))
                .subscribe(response -> System.out.println("Published batch: " + response));
    }
}
```

## Requirements

- Java 17+
- Spring Boot 3+
- AWS SDK v2

## Building from Source

```bash
mvn clean install
```
