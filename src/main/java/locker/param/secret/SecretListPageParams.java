package locker.param.secret;

import com.google.gson.annotations.SerializedName;
import locker.net.CliRequestParams;

/**
 * Parameters for one bounded page of Locker secrets.
 */
public final class SecretListPageParams extends CliRequestParams {
    private static final int MAX_PAGE_SIZE = 1000;
    private static final int MAX_CURSOR_LENGTH = 4096;

    @SerializedName("environment_name")
    private final String environmentName;

    @SerializedName("page_size")
    private final Integer pageSize;

    @SerializedName("cursor")
    private final String cursor;

    private SecretListPageParams(
            String environmentName,
            Integer pageSize,
            String cursor
    ) {
        if (environmentName != null
                && (environmentName.isEmpty()
                || environmentName.length() > 65536)) {
            throw new IllegalArgumentException(
                    "environmentName must contain between 1 and 65536 characters"
            );
        }
        if (pageSize != null && (pageSize < 1 || pageSize > MAX_PAGE_SIZE)) {
            throw new IllegalArgumentException(
                    "pageSize must be between 1 and 1000"
            );
        }
        if (cursor != null
                && (cursor.isEmpty()
                || cursor.length() > MAX_CURSOR_LENGTH)) {
            throw new IllegalArgumentException(
                    "cursor must contain between 1 and 4096 characters"
            );
        }
        this.environmentName = environmentName;
        this.pageSize = pageSize;
        this.cursor = cursor;
    }

    public String getEnvironmentName() {
        return environmentName;
    }

    public Integer getPageSize() {
        return pageSize;
    }

    public String getCursor() {
        return cursor;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String environmentName;
        private Integer pageSize;
        private String cursor;

        public Builder setEnvironmentName(String environmentName) {
            this.environmentName = environmentName;
            return this;
        }

        public Builder setPageSize(Integer pageSize) {
            this.pageSize = pageSize;
            return this;
        }

        public Builder setCursor(String cursor) {
            this.cursor = cursor;
            return this;
        }

        public SecretListPageParams build() {
            return new SecretListPageParams(
                    environmentName,
                    pageSize,
                    cursor
            );
        }
    }
}
