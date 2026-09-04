package io.agentteams.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.Locale;

/** Creates Micrometer spans from the W3C traceparent carried by durable messages. */
final class W3cSpanContext {

    private W3cSpanContext() {
    }

    static Span.Builder child(Tracer tracer, String traceparent, String name) {
        Parsed parsed = parse(traceparent);
        Span.Builder builder = tracer.spanBuilder();
        if (builder == null) {
            return null;
        }
        if (parsed != null) {
            builder.setParent(tracer.traceContextBuilder()
                    .traceId(parsed.traceId())
                    .parentId(parsed.spanId())
                    .spanId(parsed.spanId())
                    .sampled(parsed.sampled())
                    .build());
        }
        return builder.name(name);
    }

    static String traceparent(io.micrometer.tracing.TraceContext context) {
        if (context == null || !validHex(context.traceId(), 32) || !validHex(context.spanId(), 16)) {
            return "";
        }
        String flags = Boolean.TRUE.equals(context.sampled()) ? "01" : "00";
        return "00-" + context.traceId().toLowerCase(Locale.ROOT) + "-"
                + context.spanId().toLowerCase(Locale.ROOT) + "-" + flags;
    }

    private static Parsed parse(String value) {
        if (value == null) {
            return null;
        }
        String[] parts = value.trim().split("-", -1);
        if (parts.length != 4 || !"00".equalsIgnoreCase(parts[0])
                || !validHex(parts[1], 32) || !validHex(parts[2], 16) || !validHex(parts[3], 2)
                || allZero(parts[1]) || allZero(parts[2])) {
            return null;
        }
        return new Parsed(parts[1].toLowerCase(Locale.ROOT), parts[2].toLowerCase(Locale.ROOT),
                (Integer.parseInt(parts[3], 16) & 1) == 1);
    }

    private static boolean validHex(String value, int length) {
        return value != null && value.length() == length && value.chars().allMatch(c ->
                c >= '0' && c <= '9' || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F');
    }

    private static boolean allZero(String value) {
        return value.chars().allMatch(c -> c == '0');
    }

    private record Parsed(String traceId, String spanId, boolean sampled) {
    }
}
