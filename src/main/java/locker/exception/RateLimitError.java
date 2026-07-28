package locker.exception;

public class RateLimitError extends LockerError {
    private static final long serialVersionUID = 1L;

    public RateLimitError(String message, String jsonBody, String errorCode) {
        super(message, jsonBody, errorCode);
    }

    protected RateLimitError(String message) {
        super(message);
    }

    public RateLimitError(
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
