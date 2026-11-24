package cn.linke;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

@Configuration
class SecurityConfig {
    static String origin(String value, boolean production) {
        java.net.URI uri = java.net.URI.create(value);
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null ||
            uri.getFragment() != null || !uri.getPath().isEmpty() || uri.getPort() > 65535 ||
            !("https".equals(uri.getScheme()) || (!production && "http".equals(uri.getScheme()))))
            throw new IllegalArgumentException("站点地址必须是完整的 HTTP(S) 来源，生产环境须使用 HTTPS");
        return value;
    }

    @Bean
    org.springframework.web.cors.UrlBasedCorsConfigurationSource corsConfigurationSource(
            @Value("${app.base-url}") String base, @Value("${app.public-base-url}") String publicBase,
            @Value("${app.cors-origins:}") String extra, @Value("${app.secure-cookies}") boolean secure,
            org.springframework.core.env.Environment environment) {
        boolean production = environment.matchesProfiles("prod");
        if (production && !secure) throw new IllegalArgumentException("生产环境须启用安全 Cookie");
        var allowed = new java.util.LinkedHashSet<String>();
        allowed.add(origin(base, production));
        origin(publicBase, production);
        if (!extra.isBlank()) for (String item : extra.split(",", -1)) allowed.add(origin(item.trim(), production));
        var cors = new org.springframework.web.cors.CorsConfiguration();
        cors.setAllowedOrigins(java.util.List.copyOf(allowed));
        cors.setAllowCredentials(true);
        cors.setAllowedMethods(java.util.List.of("GET","HEAD","POST","PUT","PATCH","DELETE","OPTIONS"));
        cors.setAllowedHeaders(java.util.List.of("Content-Type","X-Account-Id","X-XSRF-TOKEN","Idempotency-Key","Accept"));
        var source = new org.springframework.web.cors.UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http, @Value("${app.secure-cookies}") boolean secure) throws Exception {
        var csrf = new CookieCsrfTokenRepository();
        csrf.setCookieCustomizer(c -> c.httpOnly(true).secure(secure).sameSite("Lax").path("/"));
        // /auth/csrf returns the raw token in JSON; the SPA sends X-XSRF-TOKEN.
        // Authentication uses revocable database sessions, never a client-supplied userId.
        return http.cors(org.springframework.security.config.Customizer.withDefaults()).sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(c -> c.csrfTokenRepository(csrf).csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
            .authorizeHttpRequests(a -> a.anyRequest().permitAll())
            .formLogin(f -> f.disable()).httpBasic(b -> b.disable()).logout(l -> l.disable())
            .requestCache(c -> c.disable())
            .exceptionHandling(e -> e.accessDeniedHandler((req, res, ex) -> {
                res.setStatus(403); res.setContentType("application/json;charset=UTF-8");
                res.getWriter().write("{\"code\":\"CSRF_INVALID\",\"message\":\"页面安全凭据已失效，请重试\"}");
            }))
            .headers(h -> h.cacheControl(c -> {}).referrerPolicy(r ->
                r.policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
            .build();
    }
}
