package io.clype.reactorsns.model;

import java.util.List;

import software.amazon.awssdk.services.sns.model.BatchResultErrorEntry;

/**
 * Exception thrown when some messages in a batch fail to publish.
 *
 * <p>This exception provides structured access to failure details, allowing
 * consumers to programmatically handle partial failures.</p>
 */
public class PartialBatchFailureException extends RuntimeException {

    private final List<BatchResultErrorEntry> failedEntries;
    private final int successCount;
    private final int failureCount;

    /**
     * Creates a new PartialBatchFailureException.
     *
     * @param failedEntries the list of failed batch entries from AWS
     * @param successCount  the number of messages that succeeded
     */
    public PartialBatchFailureException(List<BatchResultErrorEntry> failedEntries, int successCount) {
        super("Partial batch failure: " + failedEntries.size() + " of " +
              (failedEntries.size() + successCount) + " messages failed");
        this.failedEntries = List.copyOf(failedEntries);
        this.successCount = successCount;
        this.failureCount = failedEntries.size();
    }

    /**
     * Returns the list of failed entries with error details.
     *
     * @return immutable list of failed entries
     */
    public List<BatchResultErrorEntry> getFailedEntries() {
        return failedEntries;
    }

    /**
     * Returns the number of messages that succeeded in the batch.
     *
     * @return success count
     */
    public int getSuccessCount() {
        return successCount;
    }

    /**
     * Returns the number of messages that failed in the batch.
     *
     * @return failure count
     */
    public int getFailureCount() {
        return failureCount;
    }
}
