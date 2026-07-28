package locker.model;

import locker.net.LockerResponse;

import java.util.ArrayList;

public class LockerCollection<T>
        extends ArrayList<T>
        implements LockerCollectionInterface<T> {
    private static final long serialVersionUID = 1L;

    @Override
    public LockerResponse getLastResponse() {
        return null;
    }
}
