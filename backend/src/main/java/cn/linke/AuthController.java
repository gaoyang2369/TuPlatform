package cn.linke;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class AuthController {
    private final AuthService auth;
    private final RateLimits limits;
    private final boolean secure;
    AuthController(AuthService auth, RateLimits limits, @Value("${app.secure-cookies}") boolean secure) {
        this.auth = auth; this.limits = limits; this.secure = secure;
    }
    static String cookie(HttpServletRequest req, String name) {
        if (req.getCookies() != null) for (var c : req.getCookies()) if (name.equals(c.getName())) return c.getValue();
        return null;
    }
    static String session(HttpServletRequest req) { return cookie(req, "LK_SESSION"); }
    private void cookie(HttpServletResponse res, String name, String value, Duration age) {
        res.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(name, value).httpOnly(true).secure(secure).sameSite("Lax").path("/").maxAge(age).build().toString());
    }
    private void limit(HttpServletRequest req, String action, String subject, int maximum) {
        // Remote address only; untrusted Forwarded/X-Forwarded-For headers cannot bypass limits.
        limits.check(action + ":ip", req.getRemoteAddr(), Math.max(20, maximum * 4));
        if (subject != null) limits.check(action + ":subject", subject.toLowerCase(java.util.Locale.ROOT), maximum);
    }
    @GetMapping("/auth/csrf") Map<String, String> csrf(CsrfToken token) { return Map.of("token", token.getToken(), "headerName", token.getHeaderName()); }
    @PostMapping("/auth/register") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    Map<String, Object> register(@RequestBody RegisterBody body, HttpServletRequest req, HttpServletResponse res) {
        limit(req, "register", body.email(), 5);
        Registration r = auth.register(body);
        cookie(res, "LK_REGISTRATION", r.context(), Duration.ofDays(7));
        return Map.of("registrationFlowId", r.registrationFlowId(), "emailVerificationRequired", true, "mailAccepted", r.mailAccepted());
    }
    @PostMapping("/auth/email-verification/inspect") Map<String, Object> inspect(@RequestBody TokenBody body, HttpServletRequest req) {
        limit(req, "verify-inspect", body.token(), 40); return auth.inspect(body.token(), cookie(req, "LK_REGISTRATION"));
    }
    @PostMapping("/auth/email-verification/confirm") Map<String, Object> verify(@RequestBody VerifyBody body, HttpServletRequest req, HttpServletResponse res) {
        limit(req, "verify", body.token(), 10); auth.verify(body, cookie(req, "LK_REGISTRATION"));
        cookie(res, "LK_REGISTRATION", "", Duration.ZERO); return Map.of("verified", true, "loginRequired", true);
    }
    @PostMapping("/auth/email-verification/resend") Map<String, Object> resend(@RequestBody ResendBody body, HttpServletRequest req) {
        limit(req, "resend", body.registrationFlowId(), 3);
        return Map.of("accepted", auth.resend(body.registrationFlowId(), cookie(req, "LK_REGISTRATION")));
    }
    @PostMapping("/auth/login") Me login(@RequestBody LoginBody body, HttpServletRequest req, HttpServletResponse res) {
        limit(req, "login", body.account(), 10); LoginResult r = auth.login(body);
        auth.logout(session(req)); // Replacing an existing browser session does not leave it active.
        cookie(res, "LK_SESSION", r.session(), Duration.ofDays(7)); return r.me();
    }
    @PostMapping("/auth/logout") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    void logout(HttpServletRequest req, HttpServletResponse res) { auth.logout(session(req)); cookie(res, "LK_SESSION", "", Duration.ZERO); }
    @PostMapping("/auth/forgot-password") @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    Map<String, Object> forgot(@RequestBody ForgotBody body, HttpServletRequest req) {
        limit(req, "forgot", body.email(), 3); auth.forgot(body.email()); return Map.of("accepted", true);
    }
    @PostMapping("/auth/reset-password") Map<String, Object> reset(@RequestBody ResetBody body, HttpServletRequest req, HttpServletResponse res) {
        limit(req, "reset", body.token(), 10); auth.reset(body);
        cookie(res, "LK_SESSION", "", Duration.ZERO); return Map.of("reset", true, "loginRequired", true);
    }
    @GetMapping("/me") Me me(HttpServletRequest req) { return auth.me(auth.current(session(req), false)); }
    @PutMapping("/me/business-identity") Me identity(@RequestBody IdentityBody body, HttpServletRequest req) { return auth.identity(session(req), body); }
}
