package io.clype.reactorsns.model;

import java.util.List;
import java.util.Objects;

/**
 * Exception thrown when some messages in a batch fail to publish.
 *
 * <p>This exception provides structured access to failure details, allowing
 * consumers to programmatically handle partial failures.</p>
 */
public class PartialBatchFailureException extends RuntimeException {

    private final List<FailedEntry> failedEntries;
    private final int successCount;

    /**
     * Creates a new PartialBatchFailureException.
     *
     * @param failedEntries the list of failed entries
     * @param successCount  the number of messages that succeeded
     */
    public PartialBatchFailureException(List<FailedEntry> failedEntries, int successCount) {
        super("Partial batch failure: " +
              Objects.requireNonNull(failedEntries, "failedEntries cannot be null").size() + " of " +
              (failedEntries.size() + successCount) + " messages failed");
        this.failedEntries = List.copyOf(failedEntries);
        this.successCount = successCount;
    }

    /**
     * Returns the list of failed entries with error details.
     *
     * @return immutable list of failed entries
     */
    public List<FailedEntry> getFailedEntries() {
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
     * @return failure count (derived from failedEntries.size())
     */
    public int getFailureCount() {
        return failedEntries.size();
    }
}
