package locker.exception;


public class ApiServerError extends LockerError {
    private static final long serialVersionUID = 1L;

    public ApiServerError(String message, String jsonBody, String errorCode) {
        super(message, jsonBody, errorCode);
    }

    protected ApiServerError(String message) {
        super(message);
    }

    public ApiServerError(
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
