package io.clype.reactorsns.model;

/**
 * Status information about the SNS publisher.
 *
 * <p>This record provides introspection into the publisher's current state,
 * useful for monitoring and agent-based automation.</p>
 *
 * @param activeRequests  number of in-flight publish requests
 * @param partitionCount  configured number of partitions
 * @param bufferSize      configured main buffer size
 */
public record PublisherStatus(
    int activeRequests,
    int partitionCount,
    int bufferSize
) {}
