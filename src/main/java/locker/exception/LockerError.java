package locker.exception;


public abstract class LockerError extends Exception {


    private String errorCode;

    private String jsonBody;

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
        super(message, e);
        this.jsonBody = jsonBody;
        this.errorCode = errorCode;

    }

    protected LockerError(String message, Throwable e) {
        super(message, e);
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
}
