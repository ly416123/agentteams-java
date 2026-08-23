package io.agentteams.controlplane.security;

/** Rejects inline credentials while allowing provider-neutral secret reference paths. */
public final class CredentialReferenceValidator {
    private CredentialReferenceValidator() {
    }

    public static String normalize(String value) {
        if (value == null || value.isBlank()) return null;
        String reference = value.trim();
        if (reference.length() > 500 || reference.chars().anyMatch(Character::isWhitespace)
                || reference.indexOf('=') >= 0) {
            throw new IllegalArgumentException("credentialRef must be a secret reference, not inline credential data");
        }
        if (!reference.matches("[A-Za-z0-9][A-Za-z0-9_./:@-]*")) {
            throw new IllegalArgumentException("credentialRef contains unsupported characters");
        }
        String lower = reference.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("sk-") || lower.contains("api_key") || lower.contains("apikey")) {
            throw new IllegalArgumentException("credentialRef must not contain an inline API key");
        }
        return reference;
    }
}
