package locker.distribution;

import locker.exception.CliRunError;

/**
 * Indicates that a managed Locker CLI distribution could not be trusted,
 * installed, or verified.
 */
public final class CliDistributionException extends CliRunError {
    private static final long serialVersionUID = 1L;

    public CliDistributionException(String message) {
        super(message);
    }

    public CliDistributionException(String message, Throwable cause) {
        super(message, cause);
    }
}
