package locker.exception;

/**
 * The Locker CLI failed a local encrypted-cache or storage operation.
 */
public final class StorageError extends CliRunError {
    private static final long serialVersionUID = 1L;

    public StorageError(String message) {
        super(message);
    }

    public StorageError(
            String message,
            String errorCode,
            int protocolCode,
            String requestId,
            boolean retryable
    ) {
        this(message, errorCode, protocolCode, requestId, retryable, null);
    }

    public StorageError(
            String message,
            String errorCode,
            int protocolCode,
            String requestId,
            boolean retryable,
            String serverRequestId
    ) {
        super(
                message,
                errorCode,
                protocolCode,
                requestId,
                retryable,
                serverRequestId
        );
    }
}
