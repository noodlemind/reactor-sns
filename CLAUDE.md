# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Build and run all tests
mvn clean verify

# Run a single test class
mvn test -Dtest=AsyncFifoSnsPublisherTest

# Run a single test method
mvn test -Dtest=AsyncFifoSnsPublisherTest#testEventsAreBatchedUpToTen

# Install to local Maven repository
mvn clean install

# Build without tests
mvn clean package -DskipTests
```

Note: Tests require `-Dnet.bytebuddy.experimental=true` (configured in pom.xml surefire plugin).

## Architecture

This is a Spring Boot auto-configuration library that provides high-throughput asynchronous publishing to AWS SNS FIFO topics.

### Core Components

**AsyncFifoSnsPublisher** (`service/AsyncFifoSnsPublisher.java`): The main publisher that:
- Accepts a `Flux<SnsEvent>` stream
- Partitions events by `messageGroupId` hash to maintain FIFO ordering per group
- Batches events (max 10 per batch, 256KB payload limit per SNS constraints)
- Uses a dedicated bounded elastic scheduler (`sns-publisher-io`) for I/O
- Implements retry with exponential backoff

**SnsPublisherAutoConfiguration** (`config/SnsPublisherAutoConfiguration.java`): Spring Boot auto-configuration that:
- Creates `SnsAsyncClient` with AWS CRT HTTP client
- Configures connection pooling (max concurrency configurable via `max-connections`, default 100)
- Disables SDK retries (retries handled at Reactor level)
- Only activates when `sns.publisher.topic-arn` property is set

**SnsPublisherProperties** (`config/SnsPublisherProperties.java`): Configuration properties bound to `sns.publisher.*` prefix.

**SnsEvent** (`model/SnsEvent.java`): Record containing `messageGroupId`, `messageDeduplicationId`, and `payload` with built-in validation.

### Key Design Decisions

- **Partitioning**: Events are distributed across configurable partitions (default 256) using hash of `messageGroupId`. Same group always maps to same partition, ensuring ordering.
- **Sequential within partition**: Uses `concatMap` within each partition to process batches sequentially, preserving FIFO order.
- **Parallel across partitions**: Uses `flatMap` with concurrency equal to partition count for parallel processing across different groups.
- **Payload splitting**: Batches exceeding 256KB are automatically split to comply with SNS limits.
