package cn.linke;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

@Component
class NotificationMail {
    private static final Logger log = LoggerFactory.getLogger(NotificationMail.class);
    private final JavaMailSender sender;
    private final ObjectMapper json;
    private final String from;
    private final boolean useSmtp;

    NotificationMail(JavaMailSender sender, ObjectMapper json, @Value("${app.mail-from}") String from,
            @Value("${app.mail-enabled}") boolean enabled, @Value("${SMTP_HOST:}") String host,
            @Value("${spring.mail.properties.mail.smtp.auth}") boolean auth,
            @Value("${spring.mail.username}") String user, @Value("${spring.mail.password}") String password) {
        this.sender = sender;
        this.json = json;
        this.from = from;
        this.useSmtp = enabled && !host.isBlank() && (!auth || (!user.isBlank() && !password.isBlank()));
    }

    String deliver(UUID id, String recipient, String title, String content, String url) throws Exception {
        String subject = "邻课 · " + title;
        if (!useSmtp) {
            log.info("本地通知邮件 {}", json.writeValueAsString(Map.of(
                "id", id, "recipient", recipient, "subject", subject, "content", content, "target_url", url)));
            return "logged";
        }
        var message = sender.createMimeMessage();
        var helper = new MimeMessageHelper(message, true, "UTF-8");
        helper.setFrom(from);
        helper.setTo(recipient);
        helper.setSubject(subject);
        helper.setText(content + "\n\n前往邻课查看：" + url, html(title, content, url));
        message.setHeader("Message-ID", "<" + id + "@notifications.linke.invalid>");
        sender.send(message);
        return "sent";
    }

    private static String html(String title, String content, String url) {
        return """
            <!doctype html><html lang="zh-CN"><body style="margin:0;background:#f8fafc;padding:24px 12px;color:#0f172a;font-family:sans-serif">
            <div style="max-width:520px;margin:auto;background:#fff;border:1px solid #e2e8f0;border-radius:16px;padding:28px">
            <strong style="color:#2563eb">邻课</strong><h1 style="font-size:22px;margin:24px 0 12px">%s</h1>
            <p style="font-size:16px;line-height:1.7;color:#475569">%s</p>
            <a href="%s" style="display:inline-block;margin:12px 0;background:#2563eb;color:#fff;padding:12px 22px;border-radius:10px;text-decoration:none">前往邻课查看</a>
            </div></body></html>
            """.formatted(HtmlUtils.htmlEscape(title), HtmlUtils.htmlEscape(content).replace("\n", "<br>"), HtmlUtils.htmlEscape(url));
    }
}
