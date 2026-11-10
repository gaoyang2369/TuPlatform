package cn.linke;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionSettingsTest {
    private MockEnvironment settings(Map<String, Object> values) throws Exception {
        var env = new MockEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("fixture", values));
        env.getPropertySources().addLast(new YamlPropertySourceLoader()
            .load("application", new ClassPathResource("application.yml")).getFirst());
        return env;
    }
    @Test void splitDatabaseAndSmtpFromAliasesResolveWithoutLoggingCredentials() throws Exception {
        var env = settings(Map.of("DB_HOST", "192.0.2.10", "DB_PORT", "5432", "DB_NAME", "TuPlatform_test",
            "DB_USER", "test_user", "DB_SSLMODE", "require", "SMTP_FROM", "test@example.invalid",
            "SMTP_SECURE", "true", "SMTP_STARTTLS", "false"));
        assertEquals("jdbc:postgresql://192.0.2.10:5432/TuPlatform_test?sslmode=require", env.getProperty("spring.datasource.url"));
        assertEquals("test_user", env.getProperty("spring.datasource.username"));
        assertEquals("test@example.invalid", env.getProperty("app.mail-from"));
        assertEquals("true", env.getProperty("spring.mail.properties.mail.smtp.ssl.enable"));
        assertEquals("false", env.getProperty("spring.mail.properties.mail.smtp.starttls.required"));
    }
    @Test void canonicalConnectionPropertiesOverrideAliases() throws Exception {
        var env = settings(Map.of("DB_URL", "jdbc:postgresql://db.invalid:5432/isolated_test?sslmode=verify-full",
            "DB_USERNAME", "canonical", "DB_USER", "alias", "MAIL_FROM", "canonical@example.invalid", "SMTP_FROM", "alias@example.invalid"));
        assertEquals("jdbc:postgresql://db.invalid:5432/isolated_test?sslmode=verify-full", env.getProperty("spring.datasource.url"));
        assertEquals("canonical", env.getProperty("spring.datasource.username"));
        assertEquals("canonical@example.invalid", env.getProperty("app.mail-from"));
    }
}
