package locker.exception;

/**
 * The CLI returned a standard JSON-RPC protocol failure.
 */
public final class ProtocolError extends CliRunError {
    private static final long serialVersionUID = 1L;

    public ProtocolError(String message) {
        super(message);
    }

    public ProtocolError(String message, Throwable cause) {
        super(message, cause);
    }

    public ProtocolError(
            String message,
            String errorCode,
            int protocolCode,
            String requestId,
            boolean retryable
    ) {
        this(message, errorCode, protocolCode, requestId, retryable, null);
    }

    public ProtocolError(
            String message,
            String errorCode,
            int protocolCode,
            String requestId,
            boolean retryable,
            String serverRequestId
    ) {
        super(
                message,
                errorCode,
                protocolCode,
                requestId,
                retryable,
                serverRequestId
        );
    }
}
