package cn.linke;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {AuthController.class, ProfileController.class, MatchController.class}, properties = {"app.secure-cookies=false"},
    excludeAutoConfiguration = org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@Import({SecurityConfig.class, AccountContextConfig.class})
class SecurityContractTest {
    @Autowired MockMvc mvc;
    @MockitoBean AuthService auth;
    @MockitoBean ProfileService profiles;
    @MockitoBean MatchService matches;
    @MockitoBean RateLimits limits;
    // Spring Security's csrf() test helper replaces the cached filter's repository.
    // Bootstrap must inspect the real cookie repository, regardless of test order.
    @org.springframework.test.annotation.DirtiesContext(methodMode=org.springframework.test.annotation.DirtiesContext.MethodMode.BEFORE_METHOD)
    @Test void csrfBootstrapIsReadableAndNeverCached() throws Exception {
        mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk())
            .andExpect(jsonPath("$.token").isString()).andExpect(jsonPath("$.headerName").value("X-XSRF-TOKEN"))
            .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
            .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("HttpOnly")));
    }
    @Test void csrfIsRequiredEvenForLogin() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType("application/json").content("{\"account\":\"demo\",\"password\":\"secret\"}"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        verifyNoInteractions(auth);
    }
    @Test void profileDoesNotAllowAnonymousReading() throws Exception {
        when(auth.current(isNull(), eq(false))).thenThrow(new ApiError(401, "LOGIN_REQUIRED", "请先登录"));
        mvc.perform(get("/api/v1/me/profile")).andExpect(status().isUnauthorized());
        verifyNoInteractions(profiles);
    }
    @Test void switchedAccountCannotReceiveAnotherAccountsPendingForm() throws Exception {
        UUID current = UUID.randomUUID();
        when(auth.current(isNull(), eq(false))).thenReturn(new Account(current, "demo", "private@example.com", "hash", true, "REQUESTER", false, UUID.randomUUID(), "context", 0));
        mvc.perform(patch("/api/v1/me/profile").with(csrf()).header("X-Account-Id", UUID.randomUUID().toString())
            .contentType("application/json").content("{\"expectedVersion\":0,\"displayName\":\"甲\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SESSION_CHANGED"));
        verifyNoInteractions(profiles);
    }
    @Test void emailLinkGetCannotConfirmOrReset() throws Exception {
        mvc.perform(get("/api/v1/auth/email-verification/confirm")).andExpect(status().isMethodNotAllowed());
        mvc.perform(get("/api/v1/auth/reset-password")).andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(auth);
    }
    @Test void matchStartAndResponseRequireCsrfAndCurrentAccountEvenAtRootPath() throws Exception {
        UUID current=UUID.randomUUID();
        when(auth.current(isNull(),eq(false))).thenReturn(new Account(current,"demo","private@example.com","hash",true,"TUTOR",false,UUID.randomUUID(),"context",0));
        mvc.perform(post("/api/v1/matches").contentType("application/json").content("{}"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        mvc.perform(post("/api/v1/matches").with(csrf()).contentType("application/json").content("{}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SESSION_CHANGED"));
        mvc.perform(post("/api/v1/matches/"+UUID.randomUUID()+"/accept").with(csrf()).header("X-Account-Id",UUID.randomUUID().toString()).contentType("application/json").content("{}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SESSION_CHANGED"));
        verifyNoInteractions(matches);
    }
    @Test void teacherDiscoveryAndMatchDetailsRejectAnonymousAndSwitchedAccount() throws Exception {
        when(auth.current(isNull(),eq(false))).thenThrow(new ApiError(401,"LOGIN_REQUIRED","请先登录"));
        mvc.perform(get("/api/v1/teachers")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/matches/"+UUID.randomUUID())).andExpect(status().isUnauthorized());
        verifyNoInteractions(profiles,matches);
    }
    @Test void transportErrorsDoNotExposeDatabaseOrMailDetails() throws Exception {
        when(profiles.options()).thenThrow(new IllegalStateException("password=secret SQL internal"));
        mvc.perform(get("/api/v1/profile-options")).andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret"))));
    }
}
