package locker.exception;

public class ResourceNotFoundError extends LockerError {
    private static final long serialVersionUID = 1L;

    public ResourceNotFoundError(String message, String jsonBody, String errorCode) {
        super(message, jsonBody, errorCode);
    }

    protected ResourceNotFoundError(String message) {
        super(message);
    }

    public ResourceNotFoundError(
            String message,
            String errorCode,
            int protocolCode,
            String requestId,
            boolean retryable
    ) {
        this(message, errorCode, protocolCode, requestId, retryable, null);
    }

    public ResourceNotFoundError(
            String message,
            String errorCode,
            int protocolCode,
            String requestId,
            boolean retryable,
            String serverRequestId
    ) {
        super(
                message,
                null,
                errorCode,
                protocolCode,
                requestId,
                retryable,
                serverRequestId,
                null
        );
    }
}
