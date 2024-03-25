package locker.model;

import locker.net.LockerResponse;

import java.util.ArrayList;

public class LockerCollection<T>extends ArrayList<T> implements LockerCollectionInterface<T> {

    @Override
    public LockerResponse getLastResponse() {
        return null;
    }

}
