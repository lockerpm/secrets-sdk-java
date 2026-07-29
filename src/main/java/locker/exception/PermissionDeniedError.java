package locker.exception;

public class PermissionDeniedError extends LockerError {
    private static final long serialVersionUID = 1L;

    public PermissionDeniedError(String message, String jsonBody, String errorCode) {
        super(message, jsonBody, errorCode);
    }

    protected PermissionDeniedError(String message) {
        super(message);
    }

    public PermissionDeniedError(
            String message,
            String errorCode,
            int protocolCode,
            String requestId,
            boolean retryable
    ) {
        this(message, errorCode, protocolCode, requestId, retryable, null);
    }

    public PermissionDeniedError(
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
