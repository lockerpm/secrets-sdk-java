package locker.model;

import locker.net.LockerResponseGetter;

public interface LockerActiveObject {
    void setResponseGetter(LockerResponseGetter responseGetter);

    default void trySetResponseGetter(Object object, LockerResponseGetter responseGetter) {
        if (object instanceof LockerActiveObject) {
            ((LockerActiveObject) object).setResponseGetter(responseGetter);
        }
    }
}
