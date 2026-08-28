package io.agentteams.controlplane.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Strict, bounded HTTP adapter for the provider price snapshot contract. */
public final class HttpModelPriceSyncClient implements ModelPriceSyncPort {
    private final HttpClient client;
    private final ObjectMapper objectMapper;
    private final ModelPriceSyncProperties properties;

    public HttpModelPriceSyncClient(HttpClient client, ObjectMapper objectMapper, ModelPriceSyncProperties properties) {
        this.client = Objects.requireNonNull(client, "client");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.properties = Objects.requireNonNull(properties, "properties");
        properties.validate();
    }

    @Override
    public Snapshot fetch() {
        URI endpoint = properties.getEndpoint();
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(properties.getRequestTimeout())
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                byte[] bytes = readBounded(body, properties.getMaxResponseBytes());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new ModelPriceSyncException("price-sync source returned status " + response.statusCode());
                }
                return parse(bytes);
            }
        } catch (ModelPriceSyncException error) {
            throw error;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ModelPriceSyncException("price-sync request was interrupted", error);
        } catch (IOException | RuntimeException error) {
            throw new ModelPriceSyncException("price-sync request failed", error);
        }
    }

    private Snapshot parse(byte[] body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root == null || !root.isObject() || !root.path("prices").isArray()) {
                throw new ModelPriceSyncException("price-sync response must contain a prices array");
            }
            JsonNode sourceVersion = root.get("sourceVersion");
            String version = sourceVersion == null || sourceVersion.isNull() ? "unspecified" : sourceVersion.asText();
            List<Quote> quotes = new ArrayList<>();
            if (root.path("prices").size() > properties.getMaxQuotes()) {
                throw new ModelPriceSyncException("price-sync response contains too many quotes");
            }
            for (JsonNode item : root.path("prices")) quotes.add(parseQuote(item));
            return new Snapshot(version, quotes);
        } catch (ModelPriceSyncException error) {
            throw error;
        } catch (Exception error) {
            throw new ModelPriceSyncException("price-sync response is invalid", error);
        }
    }

    private static Quote parseQuote(JsonNode item) {
        if (item == null || !item.isObject()) throw new ModelPriceSyncException("price quote must be an object");
        return new Quote(text(item, "provider"), text(item, "model"), text(item, "currency"),
                decimal(item, "inputPricePerMillionTokens"), decimal(item, "outputPricePerMillionTokens"),
                instant(item, "effectiveFrom"), optionalInstant(item, "effectiveTo"));
    }

    private static String text(JsonNode item, String field) {
        JsonNode value = item.get(field);
        if (value == null || value.isNull() || !value.isValueNode() || value.asText().isBlank()) {
            throw new ModelPriceSyncException("price quote field is missing: " + field);
        }
        return value.asText();
    }

    private static BigDecimal decimal(JsonNode item, String field) {
        try { return new BigDecimal(text(item, field)); }
        catch (NumberFormatException error) { throw new ModelPriceSyncException("invalid decimal field: " + field, error); }
    }

    private static Instant instant(JsonNode item, String field) {
        try { return Instant.parse(text(item, field)); }
        catch (RuntimeException error) { throw new ModelPriceSyncException("invalid timestamp field: " + field, error); }
    }

    private static Instant optionalInstant(JsonNode item, String field) {
        JsonNode value = item.get(field);
        if (value == null || value.isNull()) return null;
        try { return Instant.parse(text(item, field)); }
        catch (RuntimeException error) { throw new ModelPriceSyncException("invalid timestamp field: " + field, error); }
    }

    private static byte[] readBounded(InputStream input, int maxBytes) throws IOException {
        byte[] buffer = new byte[8192];
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream(Math.min(maxBytes, 8192));
        int total = 0;
        for (int read; (read = input.read(buffer)) != -1;) {
            total += read;
            if (total > maxBytes) throw new ModelPriceSyncException("price-sync response exceeds configured limit");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }
}
