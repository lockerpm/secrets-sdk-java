package locker.net;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


public class CliRequest extends BaseCliRequest {
    private final Map<String, Object> params;

    private CliRequest(
            CliResource.RequestMethod method,
            List<String> cli,
            RequestOptions options,
            List<String> usage,
            Map<String, Object> params) {
        super(method, cli, options, usage);
        this.params = params == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(
                        new LinkedHashMap<>(params)
                );
    }

    public CliRequest(
            CliResource.RequestMethod method,
            List<String> cli,
            Map<String, Object> params,
            RequestOptions options
    ) {
        this(method, cli, options, null, params);
    }

    public CliRequest addUsage(String usage) {
        List<String> newUsage = new ArrayList<>();
        newUsage.addAll(this.getUsage());
        newUsage.add(usage);
        return new CliRequest(
                this.getMethod(),
                this.getCli(),
                this.getOptions(),
                newUsage,
                this.getParams());
    }

    public Map<String, Object> getParams() {
        return params;
    }
}
