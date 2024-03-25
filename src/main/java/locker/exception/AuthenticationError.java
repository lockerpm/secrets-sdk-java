package locker.exception;

public class AuthenticationError extends LockerError {

    public AuthenticationError(String message, String jsonBody, String errorCode) {
        super(message, jsonBody, errorCode);
    }

    public AuthenticationError(String message) {
        super(message);
    }
}
