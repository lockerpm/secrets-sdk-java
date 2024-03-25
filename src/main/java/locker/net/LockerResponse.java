package locker.net;

public class LockerResponse extends AbstractLockerResponse<String> {
    protected LockerResponse(int code, String body) {
        super(code, body);
    }
}
