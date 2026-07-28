package locker.net;

import lombok.Getter;

import java.util.List;
import java.util.Objects;

@Getter
class BaseCliRequest {
    private final CliResource.RequestMethod method;
    private final List<String> cli;
    private final RequestOptions options;
    private final List<String> usage;

    protected BaseCliRequest(
            CliResource.RequestMethod method,
            List<String> cli,
            RequestOptions options,
            List<String> usage
    ) {
        this.method = Objects.requireNonNull(
                method,
                "method must not be null"
        );
        this.cli = List.copyOf(
                Objects.requireNonNull(cli, "cli must not be null")
        );
        this.options = options;
        this.usage = usage == null ? List.of() : List.copyOf(usage);
    }
}
