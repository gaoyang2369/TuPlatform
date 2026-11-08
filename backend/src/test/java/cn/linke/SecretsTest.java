package cn.linke;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SecretsTest {
    private final Secrets secrets = new Secrets();
    @Test void passwordsAreSaltedAndComparedWithoutTrimmingOrTruncating() {
        String raw = "  密码🙂".repeat(5);
        String first = secrets.password(raw), second = secrets.password(raw);
        assertNotEquals(first, second);
        assertTrue(secrets.matches(raw, first));
        assertFalse(secrets.matches(raw.strip(), first));
        assertFalse(secrets.matches(null, first));
        assertFalse(secrets.matches("wrong", "invalid-format"));
    }
    @Test void bearerTokensHaveStrongEntropyAndOnlyDigestsAreStored() {
        String token = secrets.token();
        assertEquals(43, token.length()); assertNotEquals(token, secrets.token());
        assertEquals(64, secrets.digest(token).length()); assertNotEquals(token, secrets.digest(token));
    }
}
