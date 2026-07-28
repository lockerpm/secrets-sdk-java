package locker.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One bounded page returned by {@code environment.list_page}.
 */
public final class EnvironmentPage {
    private final List<Environment> items;
    private final String nextCursor;

    public EnvironmentPage(List<Environment> items, String nextCursor) {
        if (items == null) {
            throw new IllegalArgumentException("items must not be null");
        }
        if (items.size() > 1000) {
            throw new IllegalArgumentException(
                    "items must not contain more than 1000 environments"
            );
        }
        if (nextCursor != null
                && (nextCursor.isEmpty() || nextCursor.length() > 4096)) {
            throw new IllegalArgumentException(
                    "nextCursor must contain between 1 and 4096 characters"
            );
        }
        this.items = Collections.unmodifiableList(new ArrayList<>(items));
        this.nextCursor = nextCursor;
    }

    public String getObject() {
        return "environment_page";
    }

    public List<Environment> getItems() {
        return items;
    }

    public String getNextCursor() {
        return nextCursor;
    }
}
