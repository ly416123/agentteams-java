package io.agentteams.manager;

import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Normalizes provider and model metadata before it reaches governance records. */
public final class ModelIdentity {
    private static final int MAX_LENGTH = 64;
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,63}");

    private ModelIdentity() {}

    public static String read(Supplier<String> reader, String fallback) {
        Objects.requireNonNull(reader, "reader");
        try {
            return normalize(reader.get(), fallback);
        } catch (RuntimeException ignored) {
            return safeFallback(fallback);
        }
    }

    public static String normalize(String value, String fallback) {
        String safeFallback = safeFallback(fallback);
        if (value == null) {
            return safeFallback;
        }
        String candidate = value.trim();
        return isSafe(candidate) ? candidate : safeFallback;
    }

    private static String safeFallback(String fallback) {
        if (fallback == null) {
            return "unknown";
        }
        String candidate = fallback.trim();
        return isSafe(candidate) ? candidate : "unknown";
    }

    private static boolean isSafe(String value) {
        if (value.isEmpty() || value.length() > MAX_LENGTH || !SAFE_IDENTIFIER.matcher(value).matches()) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return !lower.contains("apikey")
                && !lower.contains("secret")
                && !lower.contains("password")
                && !lower.contains("credential")
                && !lower.contains("authorization")
                && !lower.contains("bearer")
                && !lower.contains("prompt")
                && !lower.contains("token")
                && !lower.startsWith("sk-")
                && !lower.startsWith("pk-");
    }
}
