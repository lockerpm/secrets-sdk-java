package locker.exception;

/**
 * The Locker operation was cancelled before a result was available.
 */
public final class OperationCancelledError extends ApiError {
    private static final long serialVersionUID = 1L;

    public OperationCancelledError(String message) {
        super(message);
    }

    public OperationCancelledError(
            String message,
            String errorCode,
            int protocolCode,
            String requestId,
            boolean retryable
    ) {
        this(message, errorCode, protocolCode, requestId, retryable, null);
    }

    public OperationCancelledError(
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
