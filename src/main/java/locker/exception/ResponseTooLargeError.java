package locker.exception;

/**
 * The operation result exceeded the negotiated protocol response limit.
 */
public final class ResponseTooLargeError extends ApiError {
    private static final long serialVersionUID = 1L;

    public ResponseTooLargeError(String message) {
        super(message);
    }

    public ResponseTooLargeError(
            String message,
            String errorCode,
            int protocolCode,
            String requestId,
            boolean retryable
    ) {
        this(message, errorCode, protocolCode, requestId, retryable, null);
    }

    public ResponseTooLargeError(
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
