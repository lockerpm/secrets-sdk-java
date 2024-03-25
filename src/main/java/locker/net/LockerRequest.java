package locker.net;

import locker.exception.LockerError;
import lombok.Getter;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Getter
public class LockerRequest {
    CliResource.RequestMethod method;
    List<String> cli;
    Map<String, Object> params;
    RequestOptions options;

    public LockerRequest(
            CliResource.RequestMethod method,
            List<String> cli,
            Map<String, Object> params,
            RequestOptions options
    ) throws LockerError {
        this.params = (params != null) ? Collections.unmodifiableMap(params) : null;
        this.options = (options != null) ? options : RequestOptions.getDefault();
        this.method = method;
        this.cli = cli;


    }

}
