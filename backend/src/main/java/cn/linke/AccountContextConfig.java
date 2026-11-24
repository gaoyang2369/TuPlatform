package cn.linke;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
class AccountContextConfig implements WebMvcConfigurer {
    private final AuthService auth;
    private final RateLimits limits;
    AccountContextConfig(AuthService auth, RateLimits limits) { this.auth = auth; this.limits = limits; }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(accountGuard(true)).addPathPatterns("/api/admin/**", "/api/v1/admin/**");
        registry.addInterceptor(accountGuard(false)).addPathPatterns("/api/verifications", "/api/v1/verifications",
            "/api/v1/upload/avatar", "/api/v1/upload/resume", "/api/upload/resume", "/api/v1/me/**", "/api/v1/teachers/**", "/api/v1/requests/**", "/api/v1/operations/**", "/api/v1/matches/**", "/api/matches/**",
            "/api/v1/reviews/**", "/api/reviews/**", "/api/teachers/*/reviews");
        registry.addInterceptor(quota("upload",12,"POST")).addPathPatterns(
            "/api/v1/upload/avatar","/api/upload/resume","/api/v1/upload/resume","/api/verifications","/api/v1/verifications");
        registry.addInterceptor(quota("intent",20,"POST")).addPathPatterns("/api/v1/matches");
        registry.addInterceptor(quota("contact",120,"GET","HEAD","PUT")).addPathPatterns("/api/v1/matches/*/contact-consent");
        registry.addInterceptor(quota("match-detail",120,"GET","HEAD")).addPathPatterns("/api/v1/matches/{id}");
    }

    private HandlerInterceptor quota(String scope, int maximum, String... methods) {
        var limited = java.util.Set.of(methods);
        return new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
                if (limited.contains(req.getMethod()))
                    limits.actor(scope, req.getRemoteAddr(), (java.util.UUID)req.getAttribute("linke.accountId"), maximum);
                return true;
            }
        };
    }

    private HandlerInterceptor accountGuard(boolean adminOnly) {
        return new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
                // A second browser tab may replace the cookie. Never write one account's pending
                // form into another account whose profile happens to have the same version number.
                String session = AuthController.session(req);
                Account a = adminOnly ? auth.admin(session, false) : auth.current(session, false);
                String expected = req.getHeader("X-Account-Id");
                boolean writes = !req.getMethod().equals("GET") && !req.getMethod().equals("HEAD");
                if ((writes && expected == null) || (expected != null && !a.id().toString().equals(expected)))
                    throw new ApiError(409, "SESSION_CHANGED", "浏览器中的账号已变化，请重新读取账号状态后操作");
                req.setAttribute("linke.accountId", a.id());
                return true;
            }
        };
    }
}
