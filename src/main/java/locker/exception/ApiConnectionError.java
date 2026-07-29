package locker.exception;

public class ApiConnectionError extends LockerError {
    private static final long serialVersionUID = 1L;

    public ApiConnectionError(String message) {
        super(message);
    }

    public ApiConnectionError(String message, String jsonBody, String errorCode) {
        super(message, jsonBody, errorCode);
    }

    public ApiConnectionError(
            String message,
            String errorCode,
            int protocolCode,
            String requestId,
            boolean retryable
    ) {
        this(message, errorCode, protocolCode, requestId, retryable, null);
    }

    public ApiConnectionError(
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
