package locker.exception;


public abstract class LockerError extends Exception {
    private static final long serialVersionUID = 1L;

    private final String errorCode;
    private final String jsonBody;
    private final Integer protocolCode;
    private final String requestId;
    private final String serverRequestId;
    private final Boolean retryable;

    protected LockerError(String message, String jsonBody, String errorCode) {
        this(message, jsonBody, errorCode, null);
    }

    protected LockerError(String message) {
        this(message, null, null, null);
    }

    /**
     * Constructs a new Locker exception with the specified details.
     */
    protected LockerError(
            String message, String jsonBody, String errorCode, Throwable e) {
        this(message, jsonBody, errorCode, null, null, null, e);
    }

    protected LockerError(
            String message,
            String jsonBody,
            String errorCode,
            Integer protocolCode,
            String requestId,
            Boolean retryable,
            Throwable cause
    ) {
        this(
                message,
                jsonBody,
                errorCode,
                protocolCode,
                requestId,
                retryable,
                null,
                cause
        );
    }

    protected LockerError(
            String message,
            String jsonBody,
            String errorCode,
            Integer protocolCode,
            String requestId,
            Boolean retryable,
            String serverRequestId,
            Throwable cause
    ) {
        super(message, cause);
        this.jsonBody = jsonBody;
        this.errorCode = errorCode;
        this.protocolCode = protocolCode;
        this.requestId = requestId;
        this.serverRequestId = serverRequestId;
        this.retryable = retryable;
    }

    protected LockerError(String message, Throwable e) {
        this(message, null, null, null, null, null, e);
    }

    @Override
    public String getMessage() {
        String additionalInfo = "";
        if (errorCode != null) {
            additionalInfo += "; code: " + errorCode;
        }
        return super.getMessage() + additionalInfo;
    }

    /**
     * Returns a description of the user facing exception
     *
     * @return a string representation of the user facing exception.
     */
    public String getUserMessage() {
        return super.getMessage();
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getJsonBody() {
        return jsonBody;
    }

    public Integer getProtocolCode() {
        return protocolCode;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getServerRequestId() {
        return serverRequestId;
    }

    public Boolean getRetryable() {
        return retryable;
    }
}
