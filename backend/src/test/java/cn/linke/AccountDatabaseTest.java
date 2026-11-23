package cn.linke;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
    @Autowired PlatformTransactionManager transactionManager;
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
    @Autowired ResumeService resumes;

    @Test void proofsRemainPrivateAcrossRejectionResubmissionAndApproval(@TempDir Path directory) throws Exception {
        var tutor = user("TUTOR");
        var parent = user("REQUESTER");
        var reviewer = user(null);
        db.update("UPDATE accounts SET operator_granted=true WHERE id IN (?,?)", reviewer.id(), tutor.id());
        var service = new VerificationService(auth, db, directory.toString());
        var tx = new TransactionTemplate(transactionManager);
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(12, 8, BufferedImage.TYPE_INT_RGB), "png", bytes);
        var file = new MockMultipartFile("file", "../../private.png", "image/png", bytes.toByteArray());
        var rollback = tx.execute(s -> {
            var item = service.submit(tutor.session(), "student_id", file);
            s.setRollbackOnly();
            return item.id();
        });
        assertFalse(Files.exists(directory.resolve(rollback + ".png")));
        assertThrows(ApiError.class, () -> service.submit(parent.session(), "student_id", file));
        var item = tx.execute(s -> service.submit(tutor.session(), "student_id", file));
        assertEquals(409, assertThrows(ApiError.class, () -> service.submit(tutor.session(), "student_id", file)).status);
        assertEquals(401, assertThrows(ApiError.class, () -> service.proof(null, item.id())).status);
        assertEquals(404, assertThrows(ApiError.class, () -> service.proof(parent.session(), item.id())).status);
        assertArrayEquals(bytes.toByteArray(), service.proof(tutor.session(), item.id()).bytes());
        assertArrayEquals(bytes.toByteArray(), service.proof(reviewer.session(), item.id()).bytes());
        var reject = new VerificationService.Decision("rejected", "请保留院校名称");
        assertEquals(403, assertThrows(ApiError.class, () -> service.queue(parent.session(), "pending")).status);
        assertEquals(403, assertThrows(ApiError.class, () -> service.review(parent.session(), item.id(), reject)).status);
        assertEquals(409, assertThrows(ApiError.class, () -> service.review(tutor.session(), item.id(), reject)).status);
        assertEquals(422, assertThrows(ApiError.class, () -> service.review(reviewer.session(), item.id(),
            new VerificationService.Decision("rejected", " "))).status);
        service.review(reviewer.session(), item.id(), reject);
        assertEquals(reject.reject_reason(), service.mine(tutor.session()).getFirst().reject_reason());
        var retry = tx.execute(s -> service.submit(tutor.session(), "student_id", file));
        var approve = new VerificationService.Decision("approved", null);
        assertFalse((boolean) profiles.get(tutor.session()).teacher().get("is_school_verified"));
        assertNotNull(service.review(reviewer.session(), retry.id(), approve).reviewed_at());
        assertTrue((boolean) profiles.get(tutor.session()).teacher().get("is_school_verified"));
        assertFalse((boolean) profiles.get(tutor.session()).teacher().get("is_skill_verified"));
        assertEquals(409, assertThrows(ApiError.class, () -> service.review(reviewer.session(), retry.id(), reject)).status);
        assertTrue(service.queue(reviewer.session(), "approved").stream().anyMatch(row -> row.id().equals(retry.id())));
        assertEquals(List.of(retry.id()), service.mine(tutor.session()).stream().map(VerificationService.Entry::id).toList());
    }

    @Test void resumeAccessTracksVisibilityReplacementDeletionAndVersion() throws Exception {
        var tutor = user("TUTOR");
        var requester = user("REQUESTER");
        var otherTutor = user("TUTOR");
        byte[] bytes;
        try (var pdf = new org.apache.pdfbox.pdmodel.PDDocument(); var out = new ByteArrayOutputStream()) {
            pdf.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            pdf.save(out);
            bytes = out.toByteArray();
        }
        var file = new MockMultipartFile("file", "水彩简历.PDF", "application/pdf", bytes);
        assertEquals(403, assertThrows(ApiError.class, () -> resumes.upload(requester.session(), null, file)).status);
        for (var invalid : List.of(
                new MockMultipartFile("file", "notes.txt", "application/pdf", bytes),
                new MockMultipartFile("file", "fake.pdf", "application/pdf", "%PDF-broken".getBytes()),
                new MockMultipartFile("file", "oversize.pdf", "application/pdf", new byte[5242881]))) {
            assertThrows(ApiError.class, () -> resumes.upload(tutor.session(), 0L, invalid));
        }
        var original = resumes.upload(tutor.session(), 0L, file);
        UUID fileId = UUID.fromString(original.resumePdfUrl().substring("/api/v1/resumes/".length()).replace(".pdf", ""));
        assertArrayEquals(bytes, resumes.read(tutor.session(), fileId).content());
        assertEquals(404, assertThrows(ApiError.class, () -> resumes.read(requester.session(), fileId)).status);
        var saved = profiles.saveTeacher(tutor.session(), ResumeFixtures.teacher(original.version(), "老师", List.of("DRAWING"),
            List.of("ONLINE"), List.of(), "绘画", null, null, ""));
        var visible = profiles.visibility(tutor.session(), new VersionBody(saved.version()), true, UUID.randomUUID().toString());
        assertArrayEquals(bytes, resumes.read(requester.session(), fileId).content());
        assertEquals(404, assertThrows(ApiError.class, () -> resumes.read(otherTutor.session(), fileId)).status);
        assertEquals(401, assertThrows(ApiError.class, () -> resumes.read(null, fileId)).status);
        var hidden = profiles.visibility(tutor.session(), new VersionBody(visible.version()), false, UUID.randomUUID().toString());
        assertEquals(404, assertThrows(ApiError.class, () -> resumes.read(requester.session(), fileId)).status);
        var replacement = resumes.upload(tutor.session(), hidden.version(), file);
        assertEquals(404, assertThrows(ApiError.class, () -> resumes.read(tutor.session(), fileId)).status);
        assertEquals(409, assertThrows(ApiError.class, () -> resumes.delete(tutor.session(), original.version())).status);
        assertEquals(403, assertThrows(ApiError.class, () -> resumes.delete(requester.session(), null)).status);
        resumes.delete(tutor.session(), replacement.version());
        assertNull(profiles.get(tutor.session()).teacher().get("resumePdfUrl"));
    }

    @Test void avatarReplacementFollowsProfileTransaction(@TempDir Path directory) throws Exception {
        User owner = user("REQUESTER");
        var avatars = new AvatarService(auth, db, directory.toString());
        var tx = new TransactionTemplate(transactionManager);
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(30, 20, BufferedImage.TYPE_INT_RGB), "png", bytes);
        var file = new MockMultipartFile("file", "synthetic.png", "image/png", bytes.toByteArray());
        var original = tx.execute(status -> avatars.upload(owner.session(), file));
        assertNotNull(original);
        assertEquals(original, profiles.get(owner.session()).avatarUrl());

        var rolledBack = tx.execute(status -> {
            String url = avatars.upload(owner.session(), file);
            status.setRollbackOnly();
            return url;
        });
        assertNotNull(rolledBack);
        assertFalse(Files.exists(directory.resolve(rolledBack.substring(AvatarService.URL_PREFIX.length()))));
        assertEquals(original, profiles.get(owner.session()).avatarUrl());
        assertTrue(Files.exists(directory.resolve(original.substring(AvatarService.URL_PREFIX.length()))));

        var replacement = tx.execute(status -> avatars.upload(owner.session(), file));
        assertNotEquals(original, replacement);
        assertEquals(replacement, auth.me(auth.current(owner.session(), false)).avatarUrl());
        assertFalse(Files.exists(directory.resolve(original.substring(AvatarService.URL_PREFIX.length()))));
        assertEquals(401, assertThrows(ApiError.class, () -> avatars.upload(null, file)).status);
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
        Profile draft = profiles.saveTeacher(tutor.session(), ResumeFixtures.teacher(0L, "", List.of(), List.of(), List.of(), "", null, null, ""));
        assertThrows(ApiError.class, () -> profiles.visibility(tutor.session(), new VersionBody(draft.version()), true, UUID.randomUUID().toString()));
        Profile filled = profiles.saveTeacher(tutor.session(), ResumeFixtures.teacher(draft.version(), "合成老师", List.of("DRAWING"), List.of("ONLINE"), List.of(), "自述资料", null, null, ""));
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
        assertThrows(ApiError.class, () -> profiles.saveTeacher(tutor.session(), ResumeFixtures.teacher(filled.version(), "旧版本", List.of(), List.of(), List.of(), "", null, null, "")));
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
