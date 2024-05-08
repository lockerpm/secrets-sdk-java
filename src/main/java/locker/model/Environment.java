package locker.model;

import com.google.gson.annotations.SerializedName;
import locker.net.CliResource;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@EqualsAndHashCode(callSuper = false)
public class Environment extends CliResource implements HasId {
    @SerializedName("id")
    String id;

    @SerializedName("object")
    String object;

    @SerializedName("creation_date")
    Float creationDate;

    @SerializedName("revision_date")
    Float revisionDate;

    @SerializedName("update_date")
    Float updateDate;

    @SerializedName("name")
    String name;

    @SerializedName("external_url")
    String externalUrl;

    @SerializedName("description")
    String description;

    @SerializedName("hash")
    String hash;
}
