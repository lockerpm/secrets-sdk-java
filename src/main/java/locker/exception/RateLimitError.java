package locker.exception;

public class RateLimitError extends LockerError {

    public RateLimitError(String message, String jsonBody, String errorCode) {
        super(message, jsonBody, errorCode);
    }

    protected RateLimitError(String message) {
        super(message);
    }
}
