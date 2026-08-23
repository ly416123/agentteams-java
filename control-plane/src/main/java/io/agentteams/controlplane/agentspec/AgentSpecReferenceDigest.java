package io.agentteams.controlplane.agentspec;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Stable digest helper for catalog metadata that does not carry a package digest. */
final class AgentSpecReferenceDigest {
    private AgentSpecReferenceDigest() { }

    static String derived(AgentSpecReference reference, String revision) {
        String input = reference.type().name() + "\u0000" + reference.value() + "\u0000" + revision;
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
