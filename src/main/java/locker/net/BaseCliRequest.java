package locker.net;


import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor(access = AccessLevel.PROTECTED)
class BaseCliRequest {
    private final CliResource.RequestMethod method;
    private final List<String> cli;
    private final RequestOptions options;

    // TODO (major): Remove setter and make final
    private List<String> usage;

    /**
     *
     */
    @Deprecated
    public void setUsage(List<String> usage) {
        this.usage = usage;
    }


}
