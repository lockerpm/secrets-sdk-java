package locker.model;

import com.google.gson.annotations.SerializedName;
import locker.net.CliResource;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@EqualsAndHashCode(callSuper = false)
public class Secret extends CliResource implements HasId {
    @SerializedName("id")
    String id;
    @SerializedName("object")
    String object;

    @SerializedName("creation_date")
    Float creationDate;

    @SerializedName("revision_date")
    Float revisionDate;

    @SerializedName(value = "updated_date", alternate = {"update_date"})
    Float updateDate;

    @SerializedName(value = "deleted_date", alternate = {"delete_date"})
    Float deleteDate;

    @SerializedName("last_use_date")
    Float lastUseDate;

    @SerializedName("environment_id")
    String environmentId;

    @SerializedName("environment_name")
    String environmentName;

    @SerializedName("project_id")
    String projectId;

    @SerializedName("key")
    String key;

    @SerializedName("value")
    String value;

    @SerializedName("description")
    String description;

    @SerializedName("hash")
    String hash;

    /**
     * Returns a diagnostic representation that never includes the secret value.
     */
    @Override
    public String toString() {
        return String.format(
                "<%s@%s id=%s key=%s environment=%s value=[REDACTED]>",
                this.getClass().getName(),
                System.identityHashCode(this),
                this.id,
                this.key,
                this.environmentName
        );
    }
}
