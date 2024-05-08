package locker.param.environment;

import com.google.gson.annotations.SerializedName;
import locker.net.CliRequestParams;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;

public class EnvironmentUpdateParams extends CliRequestParams {
    @SerializedName("name")
    @Getter
    @Setter
    public String name;
    @SerializedName("external_url")
    public String externalUrl;
    @SerializedName("description")
    public String description;

    private EnvironmentUpdateParams(String name, String externalUrl, String description) {
        this.name = name;
        this.externalUrl = externalUrl;
        this.description = description;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public ArrayList<String> buildCliOptions() {
        ArrayList<String> cliOptions = new ArrayList<>();
        if (this.name != null && !this.name.isEmpty()) {
            cliOptions.add("--new-name");
            cliOptions.add(this.name );
        }
        if (this.externalUrl != null && !this.externalUrl.isEmpty()) {
            cliOptions.add("--new-url");
            cliOptions.add(this.externalUrl);
        }
        if (this.description != null && !this.description.isEmpty()) {
            cliOptions.add("--new-description");
            cliOptions.add( this.description );
        }
        return cliOptions;
    }

    public static class Builder {
        private String name;
        private String externalUrl;
        private String description;

        public EnvironmentUpdateParams build() {
            return new EnvironmentUpdateParams(
                    this.name,
                    this.externalUrl,
                    this.description
            );
        }

        public Builder setName(String name) {
            this.name = name;
            return this;
        }

        public Builder setExternalUrl(String externalUrl) {
            this.externalUrl = externalUrl;
            return this;
        }

        public Builder setDescription(String description) {
            this.description = description;
            return this;
        }
    }
}
