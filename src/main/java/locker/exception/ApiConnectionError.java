package locker.exception;

public class ApiConnectionError extends LockerError {

    public ApiConnectionError(String message) {
        super(message);
    }

    public ApiConnectionError(String message, String jsonBody, String errorCode) {
        super(message, jsonBody, errorCode);
    }
}
