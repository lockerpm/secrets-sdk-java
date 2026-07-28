package locker.net;

final class CliProcessException extends Exception {
    private static final long serialVersionUID = 1L;

    enum Reason {
        REQUEST_TOO_LARGE,
        START_FAILED,
        TIMEOUT,
        INTERRUPTED,
        OUTPUT_LIMIT,
        IO_FAILURE
    }

    private final Reason reason;

    CliProcessException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    CliProcessException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    Reason getReason() {
        return reason;
    }
}
