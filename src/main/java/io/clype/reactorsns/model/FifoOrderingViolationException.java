package io.clype.reactorsns.model;

import java.util.List;

/**
 * Thrown when a partial batch failure would violate FIFO ordering guarantees.
 *
 * <p>This occurs when SNS returns a partial success where a message at position N
 * failed but a message at position M (where M > N) succeeded within the same
 * messageGroupId. Since the later message was already delivered, retrying would
 * cause out-of-order delivery.</p>
 *
 * <p>This exception is <b>not retryable</b> because the FIFO violation has already
 * occurred at the SNS level. The caller must handle this error, potentially by
 * alerting operations or implementing compensating logic.</p>
 */
public class FifoOrderingViolationException extends RuntimeException {

    private final String messageGroupId;
    private final List<FailedEntry> failedEntries;
    private final int successCount;

    public FifoOrderingViolationException(
            String messageGroupId,
            List<FailedEntry> failedEntries,
            int successCount) {
        super(String.format(
                "FIFO ordering violation detected for messageGroupId '%s': %d messages failed " +
                "but %d messages at later positions succeeded. Cannot retry without causing " +
                "out-of-order delivery.",
                messageGroupId, failedEntries.size(), successCount));
        this.messageGroupId = messageGroupId;
        this.failedEntries = List.copyOf(failedEntries);
        this.successCount = successCount;
    }

    public String getMessageGroupId() {
        return messageGroupId;
    }

    public List<FailedEntry> getFailedEntries() {
        return failedEntries;
    }

    public int getSuccessCount() {
        return successCount;
    }
}
