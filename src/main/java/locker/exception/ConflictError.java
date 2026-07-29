package locker.exception;

/**
 * The operation conflicts with the current Locker resource state.
 */
public class ConflictError extends ApiError {
    private static final long serialVersionUID = 1L;

    public ConflictError(String message) {
        super(message);
    }

    public ConflictError(
            String message,
            String errorCode,
            int protocolCode,
            String requestId,
            boolean retryable
    ) {
        this(
                message,
                errorCode,
                protocolCode,
                requestId,
                retryable,
                null
        );
    }

    public ConflictError(
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
