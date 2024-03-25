package locker.exception;

public class ResourceNotFoundError extends LockerError {
    public ResourceNotFoundError(String message, String jsonBody, String errorCode) {
        super(message, jsonBody, errorCode);
    }

    protected ResourceNotFoundError(String message) {
        super(message);
    }
}
