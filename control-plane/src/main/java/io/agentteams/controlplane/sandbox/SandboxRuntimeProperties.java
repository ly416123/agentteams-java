package io.agentteams.controlplane.sandbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Operational settings for the asynchronous sandbox provider and reconciler. */
@ConfigurationProperties(prefix = "agentteams.sandbox")
public class SandboxRuntimeProperties {
    private boolean enabled;
    private String provider = "fake";
    private String namespace = "agentteams";
    private int pollIntervalMs = 1000;
    private int batchSize = 16;
    private Duration renewBefore = Duration.ofMinutes(5);
    private Duration renewExtension = Duration.ofMinutes(30);
    private Duration lostAfter = Duration.ofMinutes(2);
    private Duration terminationGracePeriod = Duration.ofMinutes(2);
    private Duration operationTimeout = Duration.ofMinutes(2);
    private int maxProvisionAttempts = 5;
    private int maxTerminateAttempts = 10;
    private Duration baseRetryDelay = Duration.ofSeconds(1);
    private Duration maxRetryDelay = Duration.ofMinutes(1);
    private String isolatedRuntimeClassName = "agentteams-sandbox";
    private String hardenedRuntimeClassName = "agentteams-sandbox-hardened";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getNamespace() { return namespace; }
    public void setNamespace(String namespace) { this.namespace = namespace; }
    public int getPollIntervalMs() { return pollIntervalMs; }
    public void setPollIntervalMs(int pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public Duration getRenewBefore() { return renewBefore; }
    public void setRenewBefore(Duration renewBefore) { this.renewBefore = renewBefore; }
    public Duration getRenewExtension() { return renewExtension; }
    public void setRenewExtension(Duration renewExtension) { this.renewExtension = renewExtension; }
    public Duration getLostAfter() { return lostAfter; }
    public void setLostAfter(Duration lostAfter) { this.lostAfter = lostAfter; }
    public Duration getTerminationGracePeriod() { return terminationGracePeriod; }
    public void setTerminationGracePeriod(Duration terminationGracePeriod) { this.terminationGracePeriod = terminationGracePeriod; }
    public Duration getOperationTimeout() { return operationTimeout; }
    public void setOperationTimeout(Duration operationTimeout) { this.operationTimeout = operationTimeout; }
    public int getMaxProvisionAttempts() { return maxProvisionAttempts; }
    public void setMaxProvisionAttempts(int maxProvisionAttempts) { this.maxProvisionAttempts = maxProvisionAttempts; }
    public int getMaxTerminateAttempts() { return maxTerminateAttempts; }
    public void setMaxTerminateAttempts(int maxTerminateAttempts) { this.maxTerminateAttempts = maxTerminateAttempts; }
    public Duration getBaseRetryDelay() { return baseRetryDelay; }
    public void setBaseRetryDelay(Duration baseRetryDelay) { this.baseRetryDelay = baseRetryDelay; }
    public Duration getMaxRetryDelay() { return maxRetryDelay; }
    public void setMaxRetryDelay(Duration maxRetryDelay) { this.maxRetryDelay = maxRetryDelay; }
    public String getIsolatedRuntimeClassName() { return isolatedRuntimeClassName; }
    public void setIsolatedRuntimeClassName(String value) { this.isolatedRuntimeClassName = value; }
    public String getHardenedRuntimeClassName() { return hardenedRuntimeClassName; }
    public void setHardenedRuntimeClassName(String value) { this.hardenedRuntimeClassName = value; }

    public String runtimeClassName(io.agentteams.application.api.SandboxProfile profile) {
        return switch (profile) {
            case ISOLATED -> isolatedRuntimeClassName;
            case HARDENED -> hardenedRuntimeClassName;
            case NONE -> "";
        };
    }
}
