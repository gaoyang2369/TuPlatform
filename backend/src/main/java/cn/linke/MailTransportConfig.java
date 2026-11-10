package cn.linke;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/** Fail early on an incompatible TLS combination instead of sending credentials in clear text. */
@Configuration
class MailTransportConfig {
    MailTransportConfig(@Value("${app.mail-enabled}") boolean enabled,
        @Value("${spring.mail.port}") int port,
        @Value("${spring.mail.properties.mail.smtp.ssl.enable}") boolean implicitTls,
        @Value("${spring.mail.properties.mail.smtp.starttls.enable}") boolean startTls) {
        if (enabled && (implicitTls == startTls || (port == 465 && !implicitTls)))
            throw new IllegalArgumentException("Choose exactly one mail transport: port 465 with SMTP_SECURE=true and SMTP_STARTTLS=false, or STARTTLS with SMTP_SECURE=false");
    }
}
