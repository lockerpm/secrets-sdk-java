package locker.exception;

public class PermissionDeniedError extends LockerError {

    public PermissionDeniedError(String message, String jsonBody, String errorCode) {
        super(message, jsonBody, errorCode);
    }

    protected PermissionDeniedError(String message) {
        super(message);
    }
}
