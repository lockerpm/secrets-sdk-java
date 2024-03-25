package locker.net;

import locker.exception.LockerError;
import lombok.AccessLevel;
import lombok.Getter;

import java.lang.reflect.Type;

/**
 * The base class for all services.
 */
public class ApiService {
  @Getter(AccessLevel.PROTECTED)
  private final LockerResponseGetter responseGetter;

  protected ApiService(LockerResponseGetter responseGetter) {
    this.responseGetter = responseGetter;
  }

  protected <T> T call(CliRequest request, Type typeToken)
    throws LockerError {
    return this.getResponseGetter().request(request.addUsage("locker_client"), typeToken);
  }

}
