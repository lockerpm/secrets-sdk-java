package locker.exception;

public class ApiError extends LockerError{
    public ApiError(String message, String jsonBody, String errorCode) {
        super(message, jsonBody, errorCode);
    }

    protected ApiError(String message) {
        super(message);
    }
}
