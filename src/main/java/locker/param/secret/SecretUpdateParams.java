package locker.param.secret;

import com.google.gson.annotations.SerializedName;
import locker.net.CliRequestParams;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;

@Getter
@Setter
public class SecretUpdateParams extends CliRequestParams {
    @SerializedName("key")
    String key;
    @SerializedName("value")
    String value;
    @SerializedName("description")
    String description;
    @SerializedName("environment_name")
    String environmentName;

    private SecretUpdateParams(String key, String value, String description, String environmentName) {
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
        if (this.key != null && !this.key.isEmpty()) {
            cliOptions.add("--new-key");
            cliOptions.add(this.key);
        }

        if (this.value != null && !this.value.isEmpty()) {
            cliOptions.add("--new-value");
            cliOptions.add(this.value);
        }

        if (this.description != null && !this.description.isEmpty()) {
            cliOptions.add("--new-description");
            cliOptions.add(this.description);
        }
        if (this.environmentName != null) {
            cliOptions.add("--new-environment");
            cliOptions.add(this.environmentName);
        }
        return cliOptions;
    }

    public static class Builder {
        private String key;
        private String value;
        private String description;
        private String environmentName;

        public SecretUpdateParams build() {
            return new SecretUpdateParams(
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
