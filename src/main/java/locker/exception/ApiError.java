package locker.exception;

public class ApiError extends LockerError {
    private static final long serialVersionUID = 1L;

    public ApiError(String message, String jsonBody, String errorCode) {
        super(message, jsonBody, errorCode);
    }

    protected ApiError(String message) {
        super(message);
    }

    public ApiError(
            String message,
            String errorCode,
            int protocolCode,
            String requestId,
            boolean retryable
    ) {
        super(
                message,
                null,
                errorCode,
                protocolCode,
                requestId,
                retryable,
                null
        );
    }
}
