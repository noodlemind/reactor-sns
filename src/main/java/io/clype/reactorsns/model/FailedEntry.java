package io.clype.reactorsns.model;

/**
 * Represents a failed entry in a batch publish operation.
 *
 * <p>This record abstracts the AWS SDK's BatchResultErrorEntry to avoid
 * exposing AWS SDK types in the library's public API.</p>
 *
 * @param id          the unique identifier for this entry within the batch
 * @param code        the error code returned by AWS (e.g., "InternalError")
 * @param message     the error message describing the failure
 * @param senderFault true if the error was caused by the sender (client), false if service-side
 */
public record FailedEntry(
    String id,
    String code,
    String message,
    boolean senderFault
) {}
