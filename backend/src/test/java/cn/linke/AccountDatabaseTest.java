package cn.linke;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Opt-in PostgreSQL checks. Never runs with the normal application's DB_URL. SMTP is mocked. */
@EnabledIfEnvironmentVariable(named = "RUN_DATABASE_TESTS", matches = "true")
@SpringBootTest(properties = {"app.teacher-categories=DRAWING", "app.secure-cookies=false"})
class AccountDatabaseTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        String url = System.getenv("TEST_DB_URL");
        if (url == null || !url.matches("jdbc:postgresql://[^/]+/[A-Za-z0-9_]+_test(?:\\?.*)?"))
            throw new IllegalStateException("TEST_DB_URL must explicitly name a dedicated PostgreSQL database ending in _test");
        for (String name : List.of("TEST_DB_USERNAME", "TEST_DB_PASSWORD"))
            if (System.getenv(name) == null) throw new IllegalStateException(name + " is required");
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.datasource.username", () -> System.getenv("TEST_DB_USERNAME"));
        properties.add("spring.datasource.password", () -> System.getenv("TEST_DB_PASSWORD"));
    }
    @Autowired AuthService auth;
    @Autowired ProfileService profiles;
    @Autowired JdbcTemplate db;
    @MockitoBean MailGateway mail;
    private final List<UUID> created = new ArrayList<>();
    private String verificationToken;
    private String resetToken;
    private final String password = "合成测试密码 only-test 123";
    record User(UUID id, String session) {}
    @BeforeEach void captureMail() {
        when(mail.send(anyString(), anyString(), eq(false))).thenAnswer(call -> { verificationToken = call.getArgument(1); return true; });
        when(mail.enabled()).thenReturn(true);
        doAnswer(call -> { resetToken = call.getArgument(1); return null; }).when(mail).resetAfterCommit(anyString(), anyString());
    }
    @AfterEach void cleanup() { for (UUID id : created) db.update("DELETE FROM accounts WHERE id=?", id); }
    private User user(String identity) {
        String account = "test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 11);
        Registration registration = auth.register(new RegisterBody(account, password, account + "@example.invalid", true));
        UUID id = db.queryForObject("SELECT id FROM accounts WHERE registration_flow_id=?", UUID.class, registration.registrationFlowId());
        created.add(id);
        auth.verify(new VerifyBody(verificationToken, null), registration.context());
        LoginResult login = auth.login(new LoginBody(account, password));
        if (identity != null) auth.identity(login.session(), new IdentityBody(identity));
        return new User(id, login.session());
    }
    @Test void verificationNeedsExplicitConfirmationAndCrossBrowserPassword() {
        String account = "test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 11);
        Registration r = auth.register(new RegisterBody(account, password, account + "@example.invalid", true));
        created.add(db.queryForObject("SELECT id FROM accounts WHERE registration_flow_id=?", UUID.class, r.registrationFlowId()));
        assertEquals(true, auth.inspect(verificationToken, null).get("requiresRegistrationPassword"));
        assertThrows(ApiError.class, () -> auth.login(new LoginBody(account, password)));
        assertThrows(ApiError.class, () -> auth.verify(new VerifyBody(verificationToken, null), null));
        auth.verify(new VerifyBody(verificationToken, password), null);
        assertEquals(false, auth.inspect(verificationToken, r.context()).get("valid"));
        assertNotNull(auth.login(new LoginBody(account.toUpperCase(java.util.Locale.ROOT), password)).session());
    }
    @Test void eightCharacterPasswordTriggersVerificationMailAndCanLoginAfterConfirmation() {
        String account = "test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 11);
        String email = account + "@example.invalid", shortPassword = "Abcd1234";
        Registration registration = auth.register(new RegisterBody(account, shortPassword, email, true));
        created.add(db.queryForObject("SELECT id FROM accounts WHERE registration_flow_id=?", UUID.class, registration.registrationFlowId()));
        assertTrue(registration.mailAccepted());
        verify(mail).send(eq(email), anyString(), eq(false));
        assertNotNull(verificationToken);
        auth.verify(new VerifyBody(verificationToken, null), registration.context());
        assertNotNull(auth.login(new LoginBody(account, shortPassword)).session());
    }
    @Test void concurrentIdentityChoiceHasExactlyOneWinner() throws Exception {
        User u = user(null);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            List<Callable<Boolean>> tasks = List.of(
                () -> { start.await(); try { auth.identity(u.session(), new IdentityBody("REQUESTER")); return true; } catch (ApiError e) { assertEquals(409, e.status); return false; } },
                () -> { start.await(); try { auth.identity(u.session(), new IdentityBody("TUTOR")); return true; } catch (ApiError e) { assertEquals(409, e.status); return false; } }
            );
            var first = executor.submit(tasks.get(0)); var second = executor.submit(tasks.get(1)); start.countDown();
            assertNotEquals(first.get(15, java.util.concurrent.TimeUnit.SECONDS), second.get(15, java.util.concurrent.TimeUnit.SECONDS));
        }
        assertFalse(auth.current(u.session(), false).operator());
    }
    @Test void profilePublicationRequiresCompletenessAndUsesVersionsAndReceipts() {
        User tutor = user("TUTOR"), requester = user("REQUESTER");
        Profile draft = profiles.saveTeacher(tutor.session(), new TeacherBody(0L, "", List.of(), List.of(), List.of(), "", null, null, ""));
        assertThrows(ApiError.class, () -> profiles.visibility(tutor.session(), new VersionBody(draft.version()), true, UUID.randomUUID().toString()));
        Profile filled = profiles.saveTeacher(tutor.session(), new TeacherBody(draft.version(), "合成老师", List.of("DRAWING"), List.of("ONLINE"), List.of(), "自述资料", null, null, ""));
        assertEquals(false, filled.teacher().get("public"));
        assertThrows(ApiError.class, () -> profiles.publicTeacher(requester.session(), tutor.id()));
        String key = UUID.randomUUID().toString();
        Profile published = profiles.visibility(tutor.session(), new VersionBody(filled.version()), true, key);
        Profile repeated = profiles.visibility(tutor.session(), new VersionBody(filled.version()), true, key);
        assertEquals(published.version(), repeated.version());
        assertEquals(true, repeated.teacher().get("public"));
        var publicView = profiles.publicTeacher(requester.session(), tutor.id());
        for (String privateField : List.of("email", "phone", "wechat", "passwordHash", "account")) assertFalse(publicView.containsKey(privateField));
        assertThrows(ApiError.class, () -> profiles.publicTeacher(null, tutor.id()));
        assertThrows(ApiError.class, () -> profiles.saveTeacher(tutor.session(), new TeacherBody(filled.version(), "旧版本", List.of(), List.of(), List.of(), "", null, null, "")));
        Profile stopped = profiles.visibility(tutor.session(), new VersionBody(published.version()), false, UUID.randomUUID().toString());
        assertEquals(false, stopped.teacher().get("public"));
        assertThrows(ApiError.class, () -> profiles.publicTeacher(requester.session(), tutor.id()));
    }
    @Test void passwordResetConsumesAllResetTokensAndRevokesOldSessions() {
        User u = user("REQUESTER");
        String email = auth.current(u.session(), false).email();
        auth.forgot(email); String first = resetToken;
        auth.forgot(email); String second = resetToken;
        assertNotEquals(first, second);
        auth.reset(new ResetBody(second, "new synthetic password 456"));
        assertThrows(ApiError.class, () -> auth.current(u.session(), false));
        assertThrows(ApiError.class, () -> auth.reset(new ResetBody(first, "another synthetic password 789")));
        assertThrows(ApiError.class, () -> auth.reset(new ResetBody(second, "another synthetic password 789")));
    }
    @Test void managedOperatorLogsInWithoutMailboxAndCannotRequestEmailReset() {
        User u=user(null);
        String marker="operator_"+u.id()+"@operators.linke.invalid";
        db.update("UPDATE accounts SET operator_granted=true,email=?,email_key=? WHERE id=?",marker,marker,u.id());
        String account=db.queryForObject("SELECT account FROM accounts WHERE id=?",String.class,u.id());
        LoginResult login=auth.login(new LoginBody(account,password));
        assertTrue(login.me().operatorGranted());
        assertEquals("",login.me().email());
        assertFalse(login.me().emailVerified());
        assertNull(login.me().businessIdentity());
        auth.forgot(marker);
        verify(mail,never()).resetAfterCommit(eq(marker),anyString());
        assertEquals(0,db.queryForObject("SELECT count(*) FROM email_tokens WHERE account_id=? AND purpose='RESET'",Integer.class,u.id()));
        assertEquals(u.id(),auth.current(login.session(),false).id());
    }
    @Test void expiredVerificationAndResetTokensCannotBeConsumed() {
        String account="test_"+UUID.randomUUID().toString().replace("-","").substring(0,11);
        Registration registration=auth.register(new RegisterBody(account,password,account+"@example.invalid",true));
        UUID id=db.queryForObject("SELECT id FROM accounts WHERE registration_flow_id=?",UUID.class,registration.registrationFlowId());created.add(id);
        String verify=verificationToken;
        db.update("UPDATE email_tokens SET expires_at=clock_timestamp()-interval '1 second' WHERE account_id=?",id);
        assertEquals(false,auth.inspect(verify,registration.context()).get("valid"));
        assertEquals("LINK_INVALID",assertThrows(ApiError.class,()->auth.verify(new VerifyBody(verify,password),null)).code);
        User u=user("REQUESTER");auth.forgot(auth.current(u.session(),false).email());String reset=resetToken;
        db.update("UPDATE email_tokens SET expires_at=clock_timestamp()-interval '1 second' WHERE account_id=? AND purpose='RESET'",u.id());
        assertEquals("LINK_INVALID",assertThrows(ApiError.class,()->auth.reset(new ResetBody(reset,"expired synthetic reset password"))).code);
        assertEquals(u.id(),auth.current(u.session(),false).id());
    }
}
