package locker.exception;

public class CliRunError extends LockerError {
    private static final long serialVersionUID = 1L;

    public CliRunError(String message) {
        super(message);
    }

    public CliRunError(String message, Throwable cause) {
        super(message, cause);
    }

    public CliRunError(
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
                null
        );
    }

    public CliRunError(
            String message,
            String errorCode,
            int protocolCode,
            String requestId,
            boolean retryable,
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
    }
}
