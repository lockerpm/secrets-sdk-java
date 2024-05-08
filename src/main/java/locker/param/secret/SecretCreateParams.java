package locker.param.secret;

import com.google.gson.annotations.SerializedName;
import locker.net.CliRequestParams;

import java.util.ArrayList;

public class SecretCreateParams extends CliRequestParams {
    @SerializedName("key")
    String key;
    @SerializedName("value")
    String value;
    @SerializedName("description")
    String description;
    @SerializedName("environment_name")
    String environmentName;

    private SecretCreateParams(String key, String value, String description, String environmentName) {
        this.key = key;
        this.value = value;
        this.description = description;
        this.environmentName = environmentName;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public ArrayList<String> buildCliOptions() {
        ArrayList<String> cliOptions = new ArrayList<>();
        cliOptions.add("--key");
        cliOptions.add(this.key );
        if (this.value == null) {
            this.value = "";
        }
        cliOptions.add("--value");
        cliOptions.add( this.value );
        if (this.description != null && !this.description.isEmpty()) {
            cliOptions.add("--description");
            cliOptions.add(this.description );
        }
        if (this.environmentName != null && !this.environmentName.isEmpty()) {
            cliOptions.add("--environment");
            cliOptions.add( this.environmentName);
        }
        return cliOptions;
    }

    public static class Builder {
        private String key;
        private String value;
        private String description;
        private String environmentName;

        public SecretCreateParams build() {
            return new SecretCreateParams(
                    this.key,
                    this.value,
                    this.description,
                    this.environmentName
            );
        }

        public Builder setValue(String value) {
            this.value = value;
            return this;
        }

        public Builder setKey(String key) {
            this.key = key;
            return this;
        }

        public Builder setDescription(String description) {
            this.description = description;
            return this;
        }

        public Builder setEnvironmentName(String environmentName) {
            this.environmentName = environmentName;
            return this;
        }
    }
}
