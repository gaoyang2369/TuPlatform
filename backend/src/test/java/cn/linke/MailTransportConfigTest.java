package cn.linke;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MailTransportConfigTest {
    @Test void acceptsImplicitTlsAndStartTlsAsSeparateModes() {
        assertDoesNotThrow(() -> new MailTransportConfig(true, 465, true, false));
        assertDoesNotThrow(() -> new MailTransportConfig(true, 587, false, true));
        assertDoesNotThrow(() -> new MailTransportConfig(false, 587, false, false));
    }
    @Test void rejectsAccidentalPlaintextAndConflictingModes() {
        assertThrows(IllegalArgumentException.class, () -> new MailTransportConfig(true, 465, false, true));
        assertThrows(IllegalArgumentException.class, () -> new MailTransportConfig(true, 465, true, true));
        assertThrows(IllegalArgumentException.class, () -> new MailTransportConfig(true, 587, false, false));
    }
}
