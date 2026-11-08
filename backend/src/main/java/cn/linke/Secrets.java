package cn.linke;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.stereotype.Component;

@Component
class Secrets {
    private static final SecureRandom RANDOM = new SecureRandom();
    String token() { byte[] b = new byte[32]; RANDOM.nextBytes(b); return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }
    String password(String raw) {
        byte[] salt = new byte[16]; RANDOM.nextBytes(salt);
        return "pbkdf2-sha256$600000$" + Base64.getEncoder().encodeToString(salt) + "$" + Base64.getEncoder().encodeToString(derive(raw, salt, 600000));
    }
    boolean matches(String raw, String encoded) {
        if (raw == null || raw.length() > 256) return false;
        try {
            String[] p = encoded.split("\\$");
            if (p.length != 4 || !p[0].equals("pbkdf2-sha256") || !p[1].equals("600000")) return false;
            return MessageDigest.isEqual(Base64.getDecoder().decode(p[3]), derive(raw, Base64.getDecoder().decode(p[2]), 600000));
        } catch (IllegalArgumentException e) { return false; }
    }
    private byte[] derive(String raw, byte[] salt, int iterations) {
        var spec = new PBEKeySpec(raw.toCharArray(), salt, iterations, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        catch (Exception e) { throw new IllegalStateException("Password hashing unavailable", e); }
        finally { spec.clearPassword(); }
    }
}
