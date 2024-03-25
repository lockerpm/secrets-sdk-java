package locker.net;

import static java.util.Objects.requireNonNull;

public abstract class AbstractLockerResponse<T> {
    int code;


    /**
     * The body of the response.
     */
    T body;

    public final int code() {
        return this.code;
    }


    public final T body() {
        return this.body;
    }

    protected AbstractLockerResponse(int code, T body) {

        requireNonNull(body);

        this.code = code;
        this.body = body;
    }
}
