package locker.exception;

/**
 * Locker rejected semantically invalid operation input.
 */
public final class ValidationError extends ApiError {
    private static final long serialVersionUID = 1L;

    public ValidationError(String message) {
        super(message);
    }

    public ValidationError(
            String message,
            String errorCode,
            int protocolCode,
            String requestId,
            boolean retryable
    ) {
        this(message, errorCode, protocolCode, requestId, retryable, null);
    }

    public ValidationError(
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
