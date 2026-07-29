package locker.exception;

public class RateLimitError extends LockerError {
    private static final long serialVersionUID = 1L;
    private final Integer retryAfterSeconds;

    public RateLimitError(String message, String jsonBody, String errorCode) {
        super(message, jsonBody, errorCode);
        this.retryAfterSeconds = null;
    }

    protected RateLimitError(String message) {
        super(message);
        this.retryAfterSeconds = null;
    }

    public RateLimitError(
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
                null,
                null
        );
    }

    public RateLimitError(
            String message,
            String errorCode,
            int protocolCode,
            String requestId,
            boolean retryable,
            Integer retryAfterSeconds
    ) {
        this(
                message,
                errorCode,
                protocolCode,
                requestId,
                retryable,
                retryAfterSeconds,
                null
        );
    }

    public RateLimitError(
            String message,
            String errorCode,
            int protocolCode,
            String requestId,
            boolean retryable,
            Integer retryAfterSeconds,
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
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public Integer getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
