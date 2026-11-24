package cn.linke;

import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class NotificationDispatcher {
    private final JdbcTemplate db;
    private final NotificationMail mail;
    private final TransactionTemplate tx;

    NotificationDispatcher(JdbcTemplate db, NotificationMail mail, PlatformTransactionManager transactions) {
        this.db = db;
        this.mail = mail;
        this.tx = new TransactionTemplate(transactions);
    }

    @Scheduled(cron = "${app.notification-cron:*/2 * * * * *}")
    void dispatch() {
        for (int i = 0; i < 10; i++) {
            if (!Boolean.TRUE.equals(tx.execute(status -> deliverNext()))) break;
        }
    }

    private boolean deliverNext() {
        // Lock only one notification. Other workers skip it; an interrupted transaction can be picked up again.
        var rows = db.queryForList("""
            SELECT n.id,n.title,n.content,n.target_url,n.attempts,a.email
            FROM notifications n JOIN accounts a ON a.id=n.user_id
            WHERE n.channel='email' AND n.sent_status='pending' AND n.next_attempt_at<=now()
            ORDER BY n.next_attempt_at,n.created_at,n.id LIMIT 1 FOR UPDATE OF n SKIP LOCKED
            """);
        if (rows.isEmpty()) return false;
        var row = rows.getFirst();
        UUID id = (UUID) row.get("id");
        int attempts = ((Number) row.get("attempts")).intValue() + 1;
        String result;
        try {
            result = mail.deliver(id, (String) row.get("email"), (String) row.get("title"),
                (String) row.get("content"), (String) row.get("target_url"));
        } catch (Exception e) {
            result = attempts < 3 ? "pending" : "failed";
            LoggerFactory.getLogger(NotificationDispatcher.class).warn("通知邮件发送失败 id={} attempt={} error={}",
                id, attempts, e.getClass().getSimpleName());
        }
        db.update("""
            UPDATE notifications SET sent_status=?, attempts=?, next_attempt_at=now() + (? * interval '1 minute') WHERE id=?
            """, result, attempts, attempts, id);
        return true;
    }
}
