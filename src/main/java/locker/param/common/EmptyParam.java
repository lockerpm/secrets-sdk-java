package locker.param.common;

import locker.net.CliRequestParams;

public class EmptyParam implements CliRequestParams.EnumParam {
    private final String value;

    EmptyParam(String value) {
        this.value = value;
    }

    @Override
    public String getValue() {
        return this.value;
    }
}
