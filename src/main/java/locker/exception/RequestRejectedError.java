package locker.exception;

/**
 * Locker rejected a request for a reason that an older CLI could not refine.
 */
public final class RequestRejectedError extends ApiError {
    private static final long serialVersionUID = 1L;

    public RequestRejectedError(String message) {
        super(message);
    }

    public RequestRejectedError(
            String message,
            String errorCode,
            int protocolCode,
            String requestId,
            boolean retryable
    ) {
        this(message, errorCode, protocolCode, requestId, retryable, null);
    }

    public RequestRejectedError(
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
