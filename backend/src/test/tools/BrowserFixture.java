import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.*;
import java.util.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Browser fixtures only. Requires an explicitly named _test database; never loads application .env. */
public class BrowserFixture {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static String digest(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
    private static String token() { byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static String passwordHash(String password) throws Exception {
        byte[] salt = new byte[16]; RANDOM.nextBytes(salt);
        var spec = new PBEKeySpec(password.toCharArray(), salt, 600000, 256);
        try {
            byte[] hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return "pbkdf2-sha256$600000$" + Base64.getEncoder().encodeToString(salt) + "$" + Base64.getEncoder().encodeToString(hash);
        } finally { spec.clearPassword(); }
    }
    public static void main(String[] args) throws Exception {
        String url = System.getenv("TEST_DB_URL");
        if (url == null || !url.matches("jdbc:postgresql://[^/]+/[A-Za-z0-9_]+_test(?:\\?.*)?")) throw new IllegalArgumentException("Dedicated TEST_DB_URL ending in _test is required");
        var properties = new Properties();
        properties.setProperty("user", Objects.requireNonNull(System.getenv("TEST_DB_USERNAME")));
        properties.setProperty("password", Objects.requireNonNull(System.getenv("TEST_DB_PASSWORD")));
        properties.setProperty("connectTimeout", "5"); properties.setProperty("socketTimeout", "10");
        try (Connection c = DriverManager.getConnection(url, properties)) {
            c.setAutoCommit(false);
            String action = args[0];
            if (action.equals("create")) {
                UUID id = UUID.randomUUID();
                String account = "btest_" + id.toString().replace("-", "").substring(0, 20);
                String password = "BrowserTest-" + UUID.randomUUID(), verification = token();
                String identity = Set.of("REQUESTER", "TUTOR").contains(args[1]) ? args[1] : null;
                try (var s = c.prepareStatement("INSERT INTO accounts(id,account,account_key,email,email_key,password_hash,adult_confirmed_at,email_verified,business_identity,registration_flow_id,registration_context_hash) VALUES (?,?,?,?,?,?,now(),?,?,?,?)")) {
                    s.setObject(1, id); s.setString(2, account); s.setString(3, account); s.setString(4, account + "@example.invalid"); s.setString(5, account + "@example.invalid");
                    s.setString(6, passwordHash(password)); s.setBoolean(7, !args[1].equals("PENDING")); s.setString(8, identity); s.setObject(9, UUID.randomUUID()); s.setString(10, digest(token())); s.executeUpdate();
                }
                try (var s = c.prepareStatement("INSERT INTO profiles(account_id) VALUES (?)")) { s.setObject(1, id); s.executeUpdate(); }
                if (args[1].equals("PENDING")) {
                    try (var s = c.prepareStatement("INSERT INTO email_tokens(token_hash,account_id,purpose,expires_at) VALUES (?,?,'VERIFY',now()+interval '1 hour')")) {
                        s.setString(1, digest(verification)); s.setObject(2, id); s.executeUpdate();
                    }
                }
                c.commit();
                System.out.println("{\"id\":\"" + id + "\",\"account\":\"" + account + "\",\"password\":\"" + password + "\",\"token\":\"" + verification + "\"}");
            } else if (action.equals("reset")) {
                UUID id = UUID.fromString(args[1]);
                try (var s = c.prepareStatement("SELECT 1 FROM accounts WHERE id=? AND account_key LIKE 'btest\\_%' ESCAPE '\\'")) {
                    s.setObject(1, id); try (var r = s.executeQuery()) { if (!r.next()) throw new IllegalArgumentException("Browser fixture account required"); }
                }
                String reset = token();
                try (var s = c.prepareStatement("INSERT INTO email_tokens(token_hash,account_id,purpose,expires_at) VALUES (?,?,'RESET',now()+interval '30 minutes')")) { s.setString(1, digest(reset)); s.setObject(2, id); s.executeUpdate(); }
                c.commit(); System.out.println("{\"token\":\"" + reset + "\"}");
            } else if (action.equals("cleanup")) {
                UUID id = UUID.fromString(args[1]);
                try (var s = c.prepareStatement("DELETE FROM accounts WHERE id=? AND account_key LIKE 'btest\\_%' ESCAPE '\\'")) { s.setObject(1, id); s.executeUpdate(); }
                c.commit(); System.out.println("{\"cleaned\":true}");
            } else throw new IllegalArgumentException("Unknown fixture action");
        }
    }
}
