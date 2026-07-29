package locker.exception;

/**
 * Locker rejected a response or artifact that failed integrity validation.
 */
public final class IntegrityError extends ApiError {
    private static final long serialVersionUID = 1L;

    public IntegrityError(String message) {
        super(message);
    }

    public IntegrityError(
            String message,
            String errorCode,
            int protocolCode,
            String requestId,
            boolean retryable
    ) {
        this(message, errorCode, protocolCode, requestId, retryable, null);
    }

    public IntegrityError(
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
