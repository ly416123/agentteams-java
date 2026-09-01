package io.agentteams.sdk.signing;
import java.net.URI;
public final class Canonicalizer {
    private Canonicalizer() { }
    public static String canonical(String method, URI uri, String organizationId, String userId, String timestamp, String nonce, String bodyHash) {
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        String query = uri.getRawQuery();
        return method + "\n" + path + (query == null ? "" : "?" + query) + "\n" + organizationId + "\n" + userId + "\n" + timestamp + "\n" + nonce + "\n" + bodyHash;
    }
}
