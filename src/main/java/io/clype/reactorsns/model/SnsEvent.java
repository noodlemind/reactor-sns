package io.clype.reactorsns.model;

import java.util.Objects;
import java.util.regex.Pattern;

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
 *   <li>{@code messageGroupId}: Required, 1-128 characters, alphanumeric plus hyphen and underscore</li>
 *   <li>{@code messageDeduplicationId}: Required, 1-128 characters, alphanumeric plus hyphen and underscore</li>
 *   <li>{@code payload}: Optional, max 256KB when encoded as UTF-8</li>
 * </ul>
 *
 * @param messageGroupId        the message group ID for FIFO ordering; messages with the same
 *                              group ID are delivered in order (required, 1-128 chars, pattern: [a-zA-Z0-9_-]+)
 * @param messageDeduplicationId the deduplication ID to prevent duplicate message processing
 *                              within SNS's 5-minute deduplication window (required, 1-128 chars, pattern: [a-zA-Z0-9_-]+)
 * @param payload               the message content/body (optional, max 256KB)
 */
public record SnsEvent(
    String messageGroupId,
    String messageDeduplicationId,
    String payload
) {
    /**
     * AWS SNS FIFO valid character pattern for messageGroupId and messageDeduplicationId.
     * Allowed characters: alphanumeric, hyphen, and underscore.
     */
    private static final Pattern VALID_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]+$");

    /**
     * Creates a new SnsEvent with validation.
     *
     * @param messageGroupId the message group ID for FIFO ordering
     * @param messageDeduplicationId the deduplication ID
     * @param payload the message content
     * @throws NullPointerException     if messageGroupId or messageDeduplicationId is null
     * @throws IllegalArgumentException if messageGroupId or messageDeduplicationId is empty,
     *                                  exceeds 128 characters, or contains invalid characters
     */
    public SnsEvent {
        Objects.requireNonNull(messageGroupId, "messageGroupId cannot be null");
        Objects.requireNonNull(messageDeduplicationId, "messageDeduplicationId cannot be null");
        validateId(messageGroupId, "messageGroupId");
        validateId(messageDeduplicationId, "messageDeduplicationId");
    }

    private static void validateId(String value, String fieldName) {
        if (value.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " cannot be empty");
        }
        if (value.length() > 128) {
            throw new IllegalArgumentException(fieldName + " exceeds 128 characters");
        }
        if (!VALID_ID_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    fieldName + " contains invalid characters. Allowed: alphanumeric, hyphen, underscore");
        }
    }
}
