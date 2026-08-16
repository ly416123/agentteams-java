package io.agentteams.controlplane.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** Validates request keys and creates stable, non-sensitive request fingerprints. */
@Service
public final class IdempotencyService {

    private static final char SEPARATOR = '\u001f';

    public String requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key is required");
        }
        if (key.length() > 255) {
            throw new IllegalArgumentException("Idempotency-Key must be at most 255 characters");
        }
        return key;
    }

    public String requestHash(String... values) {
        Objects.requireNonNull(values, "values");
        StringBuilder canonical = new StringBuilder();
        for (int index = 0; index < values.length; index++) {
            if (index > 0) {
                canonical.append(SEPARATOR);
            }
            if (values[index] != null) {
                canonical.append(values[index]);
            }
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
