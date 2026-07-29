package locker.exception;

/**
 * A create operation targeted a Locker resource that already exists.
 */
public final class AlreadyExistsError extends ConflictError {
    private static final long serialVersionUID = 1L;

    public AlreadyExistsError(String message) {
        super(message);
    }

    public AlreadyExistsError(
            String message,
            String errorCode,
            int protocolCode,
            String requestId,
            boolean retryable
    ) {
        this(message, errorCode, protocolCode, requestId, retryable, null);
    }

    public AlreadyExistsError(
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
