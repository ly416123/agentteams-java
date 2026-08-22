package io.agentteams.application.api;

import java.util.regex.Pattern;

/** Bounded W3C context carried across asynchronous boundaries. */
public record TraceContext(String correlationId, String traceparent, String tracestate) {
    private static final Pattern SAFE_CORRELATION = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final Pattern TRACEPARENT = Pattern.compile("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");

    public TraceContext {
        correlationId = normalize(correlationId);
        traceparent = normalizeTraceparent(traceparent);
        tracestate = tracestate == null ? "" : tracestate.trim();
        if (tracestate.length() > 512) {
            throw new IllegalArgumentException("tracestate is too long");
        }
    }

    public static TraceContext empty() {
        return new TraceContext("unknown", "", "");
    }

    public boolean present() {
        return !traceparent.isBlank();
    }

    private static String normalize(String value) {
        String normalized = value == null || value.isBlank() ? "unknown" : value.trim();
        if (!SAFE_CORRELATION.matcher(normalized).matches()) {
            throw new IllegalArgumentException("correlationId contains unsupported characters");
        }
        return normalized;
    }

    private static String normalizeTraceparent(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
        if (!normalized.isBlank() && !TRACEPARENT.matcher(normalized).matches()) {
            throw new IllegalArgumentException("traceparent is not a valid W3C value");
        }
        String[] parts = normalized.split("-");
        if (!normalized.isBlank() && (parts[1].matches("0{32}") || parts[2].matches("0{16}"))) {
            throw new IllegalArgumentException("traceparent contains an invalid zero id");
        }
        return normalized;
    }
}
