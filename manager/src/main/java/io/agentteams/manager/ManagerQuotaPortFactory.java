package io.agentteams.manager;

import io.grpc.ManagedChannel;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/** Production composition boundary for optional remote Manager quota admission. */
public final class ManagerQuotaPortFactory {
    private ManagerQuotaPortFactory() { }

    public static QuotaPort fromEnvironment(ManagedChannel channel, String managerId) {
        return from(System.getenv(), channel, managerId);
    }

    public static QuotaPort from(Map<String, String> environment, ManagedChannel channel,
            String managerId) {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(managerId, "managerId");
        if (!booleanValue(environment, "AGENTTEAMS_QUOTA_REMOTE_ENABLED", false)) {
            return QuotaPort.noop();
        }
        String timeoutValue = text(environment.get("AGENTTEAMS_QUOTA_TIMEOUT_SECONDS"));
        long seconds = timeoutValue == null ? 3 : positiveLong(timeoutValue,
                "AGENTTEAMS_QUOTA_TIMEOUT_SECONDS");
        return new GrpcQuotaPort(channel, managerId, Clock.systemUTC(), Duration.ofSeconds(seconds), () -> "");
    }

    private static boolean booleanValue(Map<String, String> environment, String name, boolean fallback) {
        String value = text(environment.get(name));
        return value == null ? fallback : Boolean.parseBoolean(value);
    }

    private static long positiveLong(String value, String name) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(name + " must be a positive integer");
        }
    }

    private static String text(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
