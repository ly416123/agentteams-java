package io.agentteams.manager;

public class ModelProviderException extends RuntimeException {
    public enum Category {
        NETWORK(true),
        TIMEOUT(true),
        RATE_LIMITED(true),
        SERVER(true),
        AUTHENTICATION(false),
        CLIENT_ERROR(false),
        PROTOCOL(false),
        INTERRUPTED(false),
        UNKNOWN(false);

        private final boolean defaultRetryable;

        Category(boolean defaultRetryable) { this.defaultRetryable = defaultRetryable; }
    }

    private final Category category;
    private final boolean retryable;
    private final int statusCode;

    public ModelProviderException(String message) {
        this(message, Category.UNKNOWN, false, -1, null);
    }

    public ModelProviderException(String message, Throwable cause) {
        this(message, Category.UNKNOWN, false, -1, cause);
    }

    public ModelProviderException(String message, Category category) {
        this(message, category, category.defaultRetryable, -1, null);
    }

    public ModelProviderException(String message, Category category, int statusCode) {
        this(message, category, category.defaultRetryable, statusCode, null);
    }

    public ModelProviderException(String message, Category category, boolean retryable, int statusCode,
            Throwable cause) {
        super(message, cause);
        this.category = java.util.Objects.requireNonNull(category, "category");
        this.retryable = retryable;
        this.statusCode = statusCode;
    }

    public Category category() { return category; }

    public boolean retryable() { return retryable; }

    /** Returns the HTTP status or {@code -1} when the failure was not an HTTP response. */
    public int statusCode() { return statusCode; }
}
