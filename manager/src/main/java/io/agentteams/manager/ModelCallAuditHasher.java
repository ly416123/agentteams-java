package io.agentteams.manager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Produces stable hashes without allowing common credential values into the hashed material. */
public final class ModelCallAuditHasher {
    private static final Pattern KEY_VALUE = Pattern.compile(
            "(?i)(api[_-]?key|authorization|bearer|token|password|secret)\\s*[:=]\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s,;}\\]]+)");
    private static final Pattern BEARER = Pattern.compile("(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]+");

    private ModelCallAuditHasher() { }

    public static String hashRedacted(String value) {
        Objects.requireNonNull(value, "value");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(redact(value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public static String redact(String value) {
        Objects.requireNonNull(value, "value");
        String redacted = replace(KEY_VALUE.matcher(value));
        return replace(BEARER.matcher(redacted));
    }

    private static String replace(Matcher matcher) {
        StringBuffer result = new StringBuffer();
        while (matcher.find()) matcher.appendReplacement(result, "[REDACTED]");
        matcher.appendTail(result);
        return result.toString();
    }
}
