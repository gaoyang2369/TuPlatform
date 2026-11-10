package cn.linke;

import java.time.ZonedDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import static org.junit.jupiter.api.Assertions.*;

/** Explicitly opt-in: sends ONE real mail to the specified recipient. No database or tokens. */
@EnabledIfEnvironmentVariable(named = "RUN_SMTP_TESTS", matches = "true")
@SpringBootTest(classes = SmtpDeliveryTest.MailOnlyConfig.class,
    properties = {"spring.main.web-application-type=none"})
class SmtpDeliveryTest {
    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration(MailSenderAutoConfiguration.class)
    @Import(MailTransportConfig.class)
    static class MailOnlyConfig {}

    @Autowired JavaMailSender sender;
    @Value("${app.mail-from}") String from;
    @Value("${SMTP_TEST_RECIPIENT}") String recipient;

    @Test void configuredSpringMailSenderAcceptsOneDiagnosticMessage() {
        Rules.email(recipient);
        var configured = assertInstanceOf(JavaMailSenderImpl.class, sender);
        assertEquals("465", String.valueOf(configured.getPort()));
        assertEquals("true", configured.getJavaMailProperties().getProperty("mail.smtp.ssl.enable"));
        assertEquals("false", configured.getJavaMailProperties().getProperty("mail.smtp.starttls.enable"));
        var message = new SimpleMailMessage();
        message.setFrom(from); message.setTo(recipient);
        message.setSubject("邻课：SMTP 接入测试（无需操作）");
        message.setText("这是一封经你授权发送的邻课邮件通道测试邮件。\n\n" +
            "本邮件不含账号密码或验证链接，也不代表注册/找回密码业务已通过验收。\n" +
            "数据库可连接后，将继续验证真实账号流程。\n\n" +
            "测试时间：" + ZonedDateTime.now(ZoneId.of("Asia/Shanghai")));
        try { configured.testConnection(); sender.send(message); }
        catch (Exception e) { fail("SMTP connection/send failed (details suppressed): " + e.getClass().getSimpleName()); }
        System.out.println("SMTP_MESSAGE_ACCEPTED_BY_SERVER: recipient inbox receipt still requires confirmation.");
    }
}
