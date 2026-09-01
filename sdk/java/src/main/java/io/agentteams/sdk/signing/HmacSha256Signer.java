package io.agentteams.sdk.signing;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
public final class HmacSha256Signer {
    private final String secret;
    public HmacSha256Signer(String secret) { if (secret == null || secret.isBlank()) throw new IllegalArgumentException("accessKeySecret must not be blank"); this.secret = secret; }
    public String sign(String canonicalRequest) { try { Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256")); return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(canonicalRequest.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException("signature calculation failed", e); } }
    public static String sha256(String value) { try { byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); StringBuilder result = new StringBuilder(64); for (byte b : digest) result.append(String.format("%02x", b)); return result.toString(); } catch (Exception e) { throw new IllegalStateException("hash calculation failed", e); } }
}
