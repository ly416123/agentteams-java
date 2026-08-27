package io.agentteams.controlplane.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Objects;
import java.util.List;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.SqlParameterValue;
import java.util.Iterator;
import java.util.Locale;

public final class JdbcSupport {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final Pattern SENSITIVE_JSON_FIELD = Pattern.compile(
            "(?i)([\\\"']?)(deepseek|api[-_ ]?key|access[-_ ]?token|token|password|secret|credential|authorization)"
                    + "(\\1\\s*:\\s*)(\\\"(?:\\\\.|[^\\\"\\\\])*\\\"|'(?:\\\\.|[^'\\\\])*'|[^,}\\s]+)");
    private static final Pattern SENSITIVE_ASSIGNMENT = Pattern.compile(
            "(?i)(?<![A-Za-z0-9])((?:deepseek[-_ ]?)?(?:api[-_ ]?key|access[-_ ]?token|token|password|secret|credential|authorization))"
                    + "(\\s*(?:=|:|%3d)\\s*)(\\\"(?:\\\\.|[^\\\"\\\\])*\\\"|'(?:\\\\.|[^'\\\\])*'|[^\\s,;&?#}\\]]+)");

    private JdbcSupport() {
    }

    public static SqlParameterValue json(String value) {
        return new SqlParameterValue(Types.OTHER, redactJsonFields(Objects.requireNonNull(value, "json")));
    }

    /** Stores a rollback snapshot only when it contains no secret-bearing fields. */
    public static SqlParameterValue jsonSnapshot(String value) {
        String required = Objects.requireNonNull(value, "json");
        try {
            JsonNode root = OBJECT_MAPPER.readTree(required);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("rollback snapshot must be a safe JSON object");
            }
            if (containsSensitiveField(root)) {
                throw new IllegalArgumentException("rollback snapshot must not contain secret material");
            }
        } catch (java.io.IOException error) {
            throw new IllegalArgumentException("rollback snapshot must be valid JSON", error);
        }
        return new SqlParameterValue(Types.OTHER, required);
    }

    private static boolean containsSensitiveField(JsonNode node) {
        if (node.isObject()) {
            Iterator<java.util.Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                java.util.Map.Entry<String, JsonNode> field = fields.next();
                if (isSensitiveField(field.getKey()) || containsSensitiveField(field.getValue())) {
                    return true;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                if (containsSensitiveField(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isSensitiveField(String field) {
        String normalized = field.toLowerCase(Locale.ROOT).replaceAll("[\\s_-]", "");
        return normalized.equals("token") || normalized.equals("apikey")
                || normalized.equals("accesstoken") || normalized.equals("password")
                || normalized.equals("secret") || normalized.equals("credential")
                || normalized.equals("authorization") || normalized.equals("deepseekapikey")
                || normalized.equals("deepseekaccesstoken");
    }

    static String jsonArray(List<String> values) {
        try {
            return OBJECT_MAPPER.writeValueAsString(values);
        } catch (java.io.IOException error) {
            throw new IllegalArgumentException("array cannot be serialized", error);
        }
    }

    static List<String> stringArray(String value) {
        try {
            return OBJECT_MAPPER.readValue(value, new TypeReference<List<String>>() { });
        } catch (java.io.IOException error) {
            throw new IllegalArgumentException("array cannot be parsed", error);
        }
    }

    static String failureMessage(String value) {
        if (value == null) {
            return null;
        }
        return redactAssignments(value);
    }

    private static String redactJsonFields(String value) {
        Matcher matcher = SENSITIVE_JSON_FIELD.matcher(value);
        StringBuffer redacted = new StringBuffer();
        while (matcher.find()) {
            String replacement = matcher.group(1) + matcher.group(2) + matcher.group(3) + "\"[REDACTED]\"";
            matcher.appendReplacement(redacted, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(redacted);
        return redacted.toString();
    }

    private static String redactAssignments(String value) {
        Matcher matcher = SENSITIVE_ASSIGNMENT.matcher(value);
        StringBuffer redacted = new StringBuffer();
        while (matcher.find()) {
            String replacement = matcher.group(1) + matcher.group(2) + "[REDACTED]";
            matcher.appendReplacement(redacted, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(redacted);
        return redacted.toString();
    }

    public static Timestamp timestamp(Instant instant) {
        return Timestamp.from(Objects.requireNonNull(instant, "instant"));
    }

    public static Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return Objects.requireNonNull(value, column + " must not be null").toInstant();
    }
}
