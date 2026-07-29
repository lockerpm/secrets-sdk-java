package locker.param.secret;

import com.google.gson.annotations.SerializedName;
import locker.net.CliRequestParams;

import java.util.ArrayList;

public class SecretListParams extends CliRequestParams {
    @SerializedName("environment_name")
    private final String environmentName;

    public SecretListParams() {
        this(null);
    }

    private SecretListParams(String environmentName) {
        this.environmentName = environmentName;
    }

    public String getEnvironmentName() {
        return environmentName;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public ArrayList<String> buildCliOptions() {
        ArrayList<String> options = new ArrayList<>();
        if (environmentName != null) {
            options.add("--environment");
            options.add(environmentName);
        }
        return options;
    }

    public static final class Builder {
        private String environmentName;

        public Builder setEnvironmentName(String environmentName) {
            this.environmentName = environmentName;
            return this;
        }

        public SecretListParams build() {
            return new SecretListParams(environmentName);
        }
    }
}
