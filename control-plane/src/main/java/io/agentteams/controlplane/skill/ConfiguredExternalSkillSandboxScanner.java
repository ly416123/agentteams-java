package io.agentteams.controlplane.skill;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Opt-in bounded/timeout adapter from the deployment-provided client to the SPI. */
@Component
@ConditionalOnProperty(name = "agentteams.skill.security-scanner.external.enabled", havingValue = "true")
@ConditionalOnBean(SkillSandboxScannerClient.class)
public final class ConfiguredExternalSkillSandboxScanner implements ExternalSkillSandboxScanner, AutoCloseable {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);
    private final SkillSandboxScannerClient client;
    private final Duration timeout;
    private final ExecutorService executor;

    @Autowired
    public ConfiguredExternalSkillSandboxScanner(SkillSandboxScannerClient client) {
        this(client, DEFAULT_TIMEOUT, daemonExecutor());
    }

    public ConfiguredExternalSkillSandboxScanner(SkillSandboxScannerClient client, Duration timeout) {
        this(client, timeout, daemonExecutor());
    }

    ConfiguredExternalSkillSandboxScanner(SkillSandboxScannerClient client, Duration timeout,
            ExecutorService executor) {
        this.client = Objects.requireNonNull(client, "client");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("timeout must be positive");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public SandboxScanResult scan(SandboxScanRequest request) {
        Objects.requireNonNull(request, "request");
        Future<SkillSandboxScannerClient.ScanResult> future = executor.submit(
                () -> client.scan(new SkillSandboxScannerClient.ScanRequest(
                        request.manifestJson(), request.archiveBytes())));
        try {
            SkillSandboxScannerClient.ScanResult result = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (result == null || result.decision() == null || result.classification() == null
                    || result.classification().isBlank()) return new SandboxScanResult(Decision.REVIEW_REQUIRED,
                    SANDBOX_INVALID_RESULT, null);
            return new SandboxScanResult(switch (result.decision()) {
                case CLEAN -> Decision.CLEAN;
                case REJECTED -> Decision.REJECTED;
                case REVIEW_REQUIRED -> Decision.REVIEW_REQUIRED;
            }, safeClassification(result.classification()), null);
        } catch (TimeoutException error) {
            future.cancel(true);
            return new SandboxScanResult(Decision.REVIEW_REQUIRED, SANDBOX_TIMEOUT, null);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return new SandboxScanResult(Decision.REVIEW_REQUIRED, SANDBOX_UNAVAILABLE, null);
        } catch (ExecutionException error) {
            return mapClientFailure(error.getCause());
        } catch (RuntimeException error) {
            return new SandboxScanResult(Decision.REVIEW_REQUIRED, SANDBOX_UNAVAILABLE, null);
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private static String safeClassification(String value) {
        if (value == null || value.isBlank()) return SANDBOX_INVALID_RESULT;
        String normalized = value.replaceAll("[^A-Za-z0-9_.:-]", "_");
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 120);
    }

    private static SandboxScanResult mapClientFailure(Throwable error) {
        if (error instanceof SkillSandboxScannerClient.TimeoutException) {
            return new SandboxScanResult(Decision.REVIEW_REQUIRED, SANDBOX_TIMEOUT, null);
        }
        if (error instanceof SkillSandboxScannerClient.InvalidResultException) {
            return new SandboxScanResult(Decision.REVIEW_REQUIRED, SANDBOX_INVALID_RESULT, null);
        }
        return new SandboxScanResult(Decision.REVIEW_REQUIRED, SANDBOX_UNAVAILABLE, null);
    }

    private static ExecutorService daemonExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "agentteams-skill-sandbox");
            thread.setDaemon(true);
            return thread;
        });
    }
}
