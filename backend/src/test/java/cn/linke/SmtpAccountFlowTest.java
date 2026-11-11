package cn.linke;

import jakarta.servlet.http.Cookie;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Explicit opt-in: real PostgreSQL + real registration and reset mail to ONE approved recipient. */
@EnabledIfEnvironmentVariable(named = "RUN_REAL_ACCOUNT_EMAIL_TESTS", matches = "true")
@SpringBootTest(properties = {"app.mail-enabled=true", "app.secure-cookies=false", "app.public-base-url=http://127.0.0.1:5174"})
@AutoConfigureMockMvc
class SmtpAccountFlowTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        String url = System.getenv("TEST_DB_URL");
        if (url == null || !url.matches("jdbc:postgresql://[^/]+/[A-Za-z0-9_]+_test(?:\\?.*)?")) throw new IllegalStateException("Dedicated _test database required");
        for (String key : java.util.List.of("TEST_DB_USERNAME", "TEST_DB_PASSWORD", "SMTP_TEST_RECIPIENT"))
            if (System.getenv(key) == null) throw new IllegalStateException(key + " is required");
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.datasource.username", () -> System.getenv("TEST_DB_USERNAME"));
        properties.add("spring.datasource.password", () -> System.getenv("TEST_DB_PASSWORD"));
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @MockitoSpyBean MailGateway mail;
    private final AtomicReference<String> verification = new AtomicReference<>(), reset = new AtomicReference<>();
    private final AtomicReference<Boolean> resetAccepted = new AtomicReference<>();
    private UUID created;
    private final String account = "mail_" + UUID.randomUUID().toString().replace("-", "").substring(0, 11);
    private final String password = "Synthetic mail test password 2026";
    @BeforeEach void captureInMemoryWithoutLoggingTokens() {
        doAnswer(invocation -> {
            String token = invocation.getArgument(1);
            boolean resetting = invocation.getArgument(2);
            boolean accepted = (Boolean) invocation.callRealMethod();
            if (resetting) { reset.set(token); resetAccepted.set(accepted); } else verification.set(token);
            return accepted;
        }).when(mail).send(eq(System.getenv("SMTP_TEST_RECIPIENT")), anyString(), anyBoolean());
    }
    @AfterEach void cleanupSyntheticAccount() {
        if (created != null) db.update("DELETE FROM accounts WHERE id=? AND account_key=?", created, account);
    }
    @Test void realRegistrationMailExplicitConfirmationLoginProfileAndResetMail() throws Exception {
        String recipient = System.getenv("SMTP_TEST_RECIPIENT");
        mvc.perform(post("/api/v1/auth/register").with(csrf()).contentType("application/json")
            .content(json.writeValueAsString(Map.of("account", account, "password", password, "email", recipient, "adultConfirmed", false))))
            .andExpect(status().isUnprocessableEntity());
        var registered = mvc.perform(post("/api/v1/auth/register").with(csrf()).contentType("application/json")
            .content(json.writeValueAsString(Map.of("account", account, "password", password, "email", recipient, "adultConfirmed", true))))
            .andExpect(status().isCreated()).andReturn();
        created = db.queryForObject("SELECT id FROM accounts WHERE account_key=?", UUID.class, account);
        assertTrue(json.readTree(registered.getResponse().getContentAsString()).get("mailAccepted").asBoolean(), "Real registration mail must be accepted");
        assertNotNull(verification.get());
        mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType("application/json")
            .content(json.writeValueAsString(Map.of("account", account, "password", password))))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("EMAIL_VERIFICATION_REQUIRED"));
        mvc.perform(post("/api/v1/auth/email-verification/inspect").with(csrf()).contentType("application/json")
            .content(json.writeValueAsString(Map.of("token", verification.get()))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(true)).andExpect(jsonPath("$.requiresRegistrationPassword").value(true));
        assertFalse(db.queryForObject("SELECT email_verified FROM accounts WHERE id=?", Boolean.class, created));
        mvc.perform(post("/api/v1/auth/email-verification/confirm").with(csrf()).contentType("application/json")
            .content(json.writeValueAsString(Map.of("token", verification.get(), "registrationPassword", password))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.loginRequired").value(true));
        var loggedIn = mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType("application/json")
            .content(json.writeValueAsString(Map.of("account", account, "password", password))))
            .andExpect(status().isOk()).andReturn();
        Cookie session = loggedIn.getResponse().getCookie("LK_SESSION"); assertNotNull(session); assertTrue(session.isHttpOnly());
        mvc.perform(put("/api/v1/me/business-identity").cookie(session).with(csrf()).header("X-Account-Id", created.toString())
            .contentType("application/json").content("{\"identity\":\"REQUESTER\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.operatorGranted").value(false));
        mvc.perform(patch("/api/v1/me/profile").cookie(session).with(csrf()).header("X-Account-Id", created.toString())
            .contentType("application/json").content("{\"expectedVersion\":0,\"displayName\":\"合成邮件联调账号\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        mvc.perform(post("/api/v1/auth/forgot-password").with(csrf()).contentType("application/json")
            .content(json.writeValueAsString(Map.of("email", recipient))))
            .andExpect(status().isAccepted());
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
        while (resetAccepted.get() == null && System.nanoTime() < deadline) Thread.sleep(100);
        assertEquals(Boolean.TRUE, resetAccepted.get(), "Real reset mail must be accepted after transaction commit");
        String nextPassword = "Changed synthetic password 2026";
        mvc.perform(post("/api/v1/auth/reset-password").with(csrf()).contentType("application/json")
            .content(json.writeValueAsString(Map.of("token", reset.get(), "newPassword", nextPassword))))
            .andExpect(status().isOk());
        mvc.perform(get("/api/v1/me").cookie(session)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/reset-password").with(csrf()).contentType("application/json")
            .content(json.writeValueAsString(Map.of("token", reset.get(), "newPassword", nextPassword))))
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("LINK_INVALID"));
        mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType("application/json")
            .content(json.writeValueAsString(Map.of("account", account, "password", password))))
            .andExpect(status().isUnauthorized());
        var newSession = mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType("application/json")
            .content(json.writeValueAsString(Map.of("account", account, "password", nextPassword))))
            .andExpect(status().isOk()).andReturn().getResponse().getCookie("LK_SESSION");
        mvc.perform(post("/api/v1/auth/logout").cookie(newSession).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/me").cookie(newSession)).andExpect(status().isUnauthorized());
        System.out.println("REAL_ACCOUNT_MAIL_FLOW_PASSED: registration and reset emails accepted; token values not logged.");
    }
}
