package locker.exception;

public class AuthenticationError extends LockerError {
    private static final long serialVersionUID = 1L;

    public AuthenticationError(String message, String jsonBody, String errorCode) {
        super(message, jsonBody, errorCode);
    }

    public AuthenticationError(String message) {
        super(message);
    }

    public AuthenticationError(
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
