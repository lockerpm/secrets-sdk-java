package locker.exception;


public class ApiServerError extends LockerError {
    public ApiServerError(String message, String jsonBody, String errorCode) {
        super(message, jsonBody, errorCode);
    }

    protected ApiServerError(String message) {
        super(message);
    }
}
