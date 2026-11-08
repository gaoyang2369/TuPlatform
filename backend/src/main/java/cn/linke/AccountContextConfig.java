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
    AccountContextConfig(AuthService auth) { this.auth = auth; }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
                // A second browser tab may replace the cookie. Never write one account's pending
                // form into another account whose profile happens to have the same version number.
                Account a = auth.current(AuthController.session(req), false);
                String expected = req.getHeader("X-Account-Id");
                boolean writes = !req.getMethod().equals("GET") && !req.getMethod().equals("HEAD");
                if ((writes && expected == null) || (expected != null && !a.id().toString().equals(expected)))
                    throw new ApiError(409, "SESSION_CHANGED", "浏览器中的账号已变化，请重新读取账号状态后操作");
                return true;
            }
        }).addPathPatterns("/api/v1/me/**", "/api/v1/teachers/**");
    }
}
