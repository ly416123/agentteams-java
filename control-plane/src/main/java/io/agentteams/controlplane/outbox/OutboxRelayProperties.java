package io.agentteams.controlplane.outbox;

import java.time.Duration;

public final class OutboxRelayProperties {

    private int concurrency = 4;
    private int batchSize = 16;
    private int maxAttempts = 10;
    private Duration claimLease = Duration.ofSeconds(30);
    private Duration baseRetryDelay = Duration.ofSeconds(1);
    private Duration maxRetryDelay = Duration.ofMinutes(5);
    private Duration shutdownTimeout = Duration.ofSeconds(30);

    public int getConcurrency() {
        return concurrency;
    }

    public void setConcurrency(int concurrency) {
        if (concurrency < 1) {
            throw new IllegalArgumentException("concurrency must be positive");
        }
        this.concurrency = concurrency;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        this.batchSize = batchSize;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        this.maxAttempts = maxAttempts;
    }

    public Duration getClaimLease() {
        return claimLease;
    }

    public void setClaimLease(Duration claimLease) {
        this.claimLease = requirePositive(claimLease, "claimLease");
    }

    public Duration getBaseRetryDelay() {
        return baseRetryDelay;
    }

    public void setBaseRetryDelay(Duration baseRetryDelay) {
        this.baseRetryDelay = requirePositive(baseRetryDelay, "baseRetryDelay");
    }

    public Duration getMaxRetryDelay() {
        return maxRetryDelay;
    }

    public void setMaxRetryDelay(Duration maxRetryDelay) {
        this.maxRetryDelay = requirePositive(maxRetryDelay, "maxRetryDelay");
    }

    public Duration getShutdownTimeout() {
        return shutdownTimeout;
    }

    public void setShutdownTimeout(Duration shutdownTimeout) {
        this.shutdownTimeout = requirePositive(shutdownTimeout, "shutdownTimeout");
    }

    public Duration retryDelayForAttempt(int attempt) {
        long multiplier = 1L << Math.min(Math.max(attempt - 1, 0), 30);
        Duration uncapped = baseRetryDelay.multipliedBy(multiplier);
        return uncapped.compareTo(maxRetryDelay) > 0 ? maxRetryDelay : uncapped;
    }

    private static Duration requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
