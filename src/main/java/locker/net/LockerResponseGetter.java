package locker.net;

import locker.exception.LockerError;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

public interface LockerResponseGetter {
    <T> T request(
            CliResource.RequestMethod method,
            List<String> cli,
            Map<String, Object> params,
            Type typeToken,
            RequestOptions options

    ) throws LockerError;

    default <T> T request(CliRequest request, Type typeToken)
            throws LockerError {
        return request(
                request.getMethod(),
                request.getCli(),
                request.getParams(),
                typeToken,
                request.getOptions()
        );
    }
}
