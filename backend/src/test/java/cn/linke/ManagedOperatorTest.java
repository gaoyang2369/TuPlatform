package cn.linke;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ManagedOperatorTest {
    private Account account(String email, boolean operator) {
        return new Account(UUID.randomUUID(), "test_operator", email, "unused", true, null,
            operator, UUID.randomUUID(), "unused", 0);
    }
    @Test void internalOperatorCannotSchedulePasswordEmailOrCreateResetToken() {
        var db = mock(JdbcTemplate.class);
        var mail = mock(MailGateway.class);
        var auth = new AuthService(db, new Secrets(), mail);
        var internal = account("test_operator@operators.linke.invalid", true);
        when(mail.enabled()).thenReturn(true);
        doReturn(List.of(internal)).when(db).query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Account>>any(), eq(internal.email()));
        auth.forgot(internal.email());
        verify(mail, never()).resetAfterCommit(anyString(), anyString());
        verify(db, never()).update(anyString(), any(Object[].class));
        Me me = auth.me(internal);
        assertEquals("", me.email());
        assertFalse(me.emailVerified());
        assertTrue(me.operatorGranted());
    }
    @Test void reservedAddressDoesNotConferOperatorStatusAndRealOperatorKeepsMailbox() {
        var auth = new AuthService(mock(JdbcTemplate.class), new Secrets(), mock(MailGateway.class));
        var ordinary = auth.me(account("unprivileged@operators.linke.invalid", false));
        assertFalse(ordinary.operatorGranted());
        assertEquals("unprivileged@operators.linke.invalid", ordinary.email());
        var withMailbox = auth.me(account("operator@example.invalid", true));
        assertEquals("operator@example.invalid", withMailbox.email());
        assertTrue(withMailbox.emailVerified());
    }
}
