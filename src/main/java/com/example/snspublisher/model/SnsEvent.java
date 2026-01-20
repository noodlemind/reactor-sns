package com.example.snspublisher.model;

import java.util.Objects;

/**
 * Represents an event to be published to an SNS FIFO topic.
 *
 * <p>This record encapsulates the data required for publishing a message to AWS SNS FIFO topics,
 * including the message group ID for ordering, deduplication ID for exactly-once delivery,
 * and the message payload.</p>
 *
 * <p><b>Usage Example:</b></p>
 * <pre>{@code
 * SnsEvent event = new SnsEvent(
 *     "order-123",                    // messageGroupId - orders with same ID are delivered in order
 *     "event-" + UUID.randomUUID(),   // messageDeduplicationId - prevents duplicate processing
 *     "{\"status\": \"shipped\"}"     // payload - the message content
 * );
 * }</pre>
 *
 * <p><b>Field Constraints (enforced by AWS SNS):</b></p>
 * <ul>
 *   <li>{@code messageGroupId}: Required, max 128 characters</li>
 *   <li>{@code messageDeduplicationId}: Required, max 128 characters</li>
 *   <li>{@code payload}: Optional, max 256KB when encoded as UTF-8</li>
 * </ul>
 *
 * @param messageGroupId        the message group ID for FIFO ordering; messages with the same
 *                              group ID are delivered in order (required, max 128 chars)
 * @param messageDeduplicationId the deduplication ID to prevent duplicate message processing
 *                              within SNS's 5-minute deduplication window (required, max 128 chars)
 * @param payload               the message content/body (optional, max 256KB)
 */
public record SnsEvent(
    String messageGroupId,
    String messageDeduplicationId,
    String payload
) {
    /**
     * Creates a new SnsEvent with validation.
     *
     * @throws NullPointerException     if messageGroupId or messageDeduplicationId is null
     * @throws IllegalArgumentException if messageGroupId or messageDeduplicationId exceeds 128 characters
     */
    public SnsEvent {
        Objects.requireNonNull(messageGroupId, "messageGroupId cannot be null");
        Objects.requireNonNull(messageDeduplicationId, "messageDeduplicationId cannot be null");
        if (messageGroupId.length() > 128) {
            throw new IllegalArgumentException("messageGroupId exceeds 128 characters");
        }
        if (messageDeduplicationId.length() > 128) {
            throw new IllegalArgumentException("messageDeduplicationId exceeds 128 characters");
        }
    }
}
