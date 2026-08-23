package io.agentteams.controlplane.service;

import io.agentteams.controlplane.security.SecretResolver;
import java.io.IOException;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

/**
 * Opt-in endpoint probe for model providers.
 *
 * <p>This probe intentionally sends no credential. A successful response means
 * only that the endpoint answered; it never means that authentication
 * succeeded. Redirects are disabled so an endpoint cannot silently escape the
 * configured host policy.</p>
 */
public final class HttpModelProviderConnectionProbe implements ModelProviderConnectionProbe {

    private final SecretResolver secretResolver;
    private final HttpClient httpClient;
    private final ModelProviderConnectionProbeProperties properties;

    public HttpModelProviderConnectionProbe(SecretResolver secretResolver, HttpClient httpClient,
            ModelProviderConnectionProbeProperties properties) {
        this.secretResolver = Objects.requireNonNull(secretResolver, "secretResolver");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.properties = Objects.requireNonNull(properties, "properties");
        properties.validate();
    }

    @Override
    public ProbeResult probe(ProbeRequest request) {
        Objects.requireNonNull(request, "request");
        URI endpoint = parseAndAuthorizeEndpoint(request.endpoint());
        if (endpoint == null) {
            return rejected("ENDPOINT_NOT_ALLOWED", "ENDPOINT", "INVALID_OR_NOT_ALLOWLISTED");
        }

        SecretResolver.Resolution credential = secretResolver.resolve(request.credentialReference());
        if (credential.status() == SecretResolver.Status.MISSING) {
            return rejected("CREDENTIAL_REFERENCE_MISSING", "CREDENTIAL_REFERENCE", "MISSING");
        }
        if (credential.status() == SecretResolver.Status.INVALID_REFERENCE) {
            return rejected("CREDENTIAL_REFERENCE_INVALID", "CREDENTIAL_REFERENCE", "INVALID");
        }
        if (credential.status() == SecretResolver.Status.UNAVAILABLE) {
            return rejected("CREDENTIAL_REFERENCE_UNAVAILABLE", "CREDENTIAL_REFERENCE", "UNAVAILABLE");
        }

        Duration timeout = effectiveTimeout(request.timeout());
        if (timeout == null) {
            return rejected("TIMEOUT_INVALID", "TIMEOUT", "INVALID");
        }

        HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<Void> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.discarding());
            return classifyHttpStatus(response.statusCode(), credential.status());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return failed("NETWORK_INTERRUPTED", "NETWORK", "INTERRUPTED");
        } catch (IOException error) {
            return classifyNetworkError(error);
        } catch (RuntimeException error) {
            return classifyNetworkError(error);
        }
    }

    private URI parseAndAuthorizeEndpoint(String value) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null ? null : uri.getHost().toLowerCase(Locale.ROOT);
            if (scheme == null || host == null || host.isBlank() || uri.getUserInfo() != null
                    || uri.getFragment() != null || uri.getPort() == 0 || uri.getPort() < -1
                    || !properties.getAllowedSchemes().stream()
                            .map(item -> item.toLowerCase(Locale.ROOT)).toList().contains(scheme)
                    || !properties.getAllowedHosts().stream()
                            .map(item -> item.toLowerCase(Locale.ROOT)).toList().contains(host)) {
                return null;
            }
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                return null;
            }
            return uri;
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    private Duration effectiveTimeout(Duration requested) {
        if (requested == null || requested.isZero() || requested.isNegative()
                || requested.compareTo(Duration.ofMillis(
                        ValidationOnlyModelProviderConnectionProbe.MAX_TIMEOUT_MILLIS)) > 0) {
            return null;
        }
        return requested.compareTo(properties.getMaxTimeout()) > 0 ? null : requested;
    }

    private ProbeResult classifyHttpStatus(int statusCode, SecretResolver.Status credentialStatus) {
        String credentialCheck = credentialStatus == SecretResolver.Status.RESOLVED
                || credentialStatus == SecretResolver.Status.VALIDATION_ONLY
                ? "RESOLVED_BUT_NOT_SENT" : credentialStatus.name();
        if (statusCode >= 200 && statusCode < 300) {
            return new ProbeResult(ProbeResult.Status.CONNECTED,
                    "ENDPOINT_REACHABLE_UNAUTHENTICATED_" + (statusCode / 100) + "XX", true,
                    List.of(new ProbeResult.Check("ENDPOINT", "REACHABLE"),
                            new ProbeResult.Check("HTTP_STATUS", (statusCode / 100) + "XX"),
                            new ProbeResult.Check("CREDENTIAL_REFERENCE", credentialCheck)));
        }
        if (statusCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
            return failed("ENDPOINT_REACHABLE_UNAUTHENTICATED_401", "HTTP_STATUS", "401");
        }
        if (statusCode == HttpURLConnection.HTTP_FORBIDDEN) {
            return failed("ENDPOINT_REACHABLE_UNAUTHENTICATED_403", "HTTP_STATUS", "403");
        }
        if (statusCode == 429) {
            return failed("ENDPOINT_REACHABLE_RATE_LIMITED_429", "HTTP_STATUS", "429");
        }
        if (statusCode >= 500 && statusCode < 600) {
            return failed("ENDPOINT_REACHABLE_UPSTREAM_5XX", "HTTP_STATUS", "5XX");
        }
        if (statusCode >= 300 && statusCode < 400) {
            return failed("REDIRECT_NOT_ALLOWED", "HTTP_STATUS", "3XX");
        }
        return failed("ENDPOINT_REACHABLE_HTTP_" + (statusCode / 100) + "XX", "HTTP_STATUS",
                (statusCode / 100) + "XX");
    }

    private static ProbeResult classifyNetworkError(Throwable error) {
        Throwable cause = error;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof HttpTimeoutException || cause instanceof TimeoutException) {
            return failed("TIMEOUT", "NETWORK", "TIMEOUT");
        }
        if (cause instanceof ConnectException) {
            return failed("NETWORK_ERROR", "NETWORK", "CONNECT_ERROR");
        }
        return failed("NETWORK_ERROR", "NETWORK", "ERROR");
    }

    private static ProbeResult rejected(String classification, String name, String status) {
        return new ProbeResult(ProbeResult.Status.REJECTED, classification, false,
                List.of(new ProbeResult.Check(name, status)));
    }

    private static ProbeResult failed(String classification, String name, String status) {
        return new ProbeResult(ProbeResult.Status.FAILED, classification, true,
                List.of(new ProbeResult.Check(name, status)));
    }

}
