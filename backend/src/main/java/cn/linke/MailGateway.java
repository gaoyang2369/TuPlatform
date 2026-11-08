package cn.linke;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
class MailGateway {
    private final JavaMailSender sender;
    private final boolean enabled;
    private final String from;
    private final String baseUrl;
    private final ThreadPoolTaskExecutor executor;
    MailGateway(JavaMailSender sender, @Value("${app.mail-enabled}") boolean enabled,
        @Value("${app.mail-from}") String from, @Value("${app.public-base-url}") String baseUrl,
        ThreadPoolTaskExecutor mailExecutor) {
        this.sender = sender; this.enabled = enabled; this.from = from;
        this.executor = mailExecutor;
        URI uri = URI.create(baseUrl);
        if (uri.getHost() == null || !java.util.Set.of("http", "https").contains(uri.getScheme()) || uri.getRawQuery() != null || uri.getRawFragment() != null)
            throw new IllegalArgumentException("PUBLIC_BASE_URL must be an HTTP(S) origin without query or fragment");
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }
    boolean enabled() { return enabled; }
    void resetAfterCommit(String email, String token) {
        // The response timing must not depend on whether this address has an SMTP recipient.
        // Raw tokens exist only in memory/mail, never in a database outbox or ordinary logs.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try { executor.execute(() -> send(email, token, true)); }
                catch (org.springframework.core.task.TaskRejectedException ignored) { /* Accepted is not delivered. User may retry. */ }
            }
        });
    }
    boolean send(String email, String token, boolean reset) {
        if (!enabled) return false;
        var message = new SimpleMailMessage();
        message.setFrom(from); message.setTo(email);
        message.setSubject(reset ? "邻课：重置密码" : "邻课：验证注册邮箱");
        // Fragment stays out of HTTP requests, access logs and referrers; GET never consumes a token.
        message.setText((reset ? "请打开页面并确认新密码：\n" : "请打开页面并明确确认邮箱验证：\n") +
            baseUrl + (reset ? "/#reset=" : "/#verify=") + token +
            "\n如非本人操作，请忽略。请勿转发链接。链接只能使用一次。" + (reset ? "有效期30分钟。" : "有效期24小时，且不超过注册后7天。"));
        try { sender.send(message); return true; }
        catch (org.springframework.mail.MailException e) {
            // Never log mail bodies, credentials or recipient addresses. The caller exposes only acceptance.
            return false;
        }
    }
}
