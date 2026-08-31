package io.agentteams.controlplane.channel;

/** Stable, low-cardinality channel failure categories exposed to callers and metrics. */
public enum ChannelErrorCategory {
    AUTH_REJECTED,
    RATE_LIMITED,
    TEMPORARILY_UNAVAILABLE,
    INVALID_RESPONSE,
    PERMANENT_REJECTION
}
