package cn.linke;

import java.net.URI;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Enqueue in the same transaction as the transition. SMTP runs only against committed rows. */
@Service
class NotificationService {
    private final JdbcTemplate db;
    private final String baseUrl;

    NotificationService(JdbcTemplate db, @Value("${app.base-url}") String baseUrl) {
        URI uri = URI.create(baseUrl);
        if (uri.getHost() == null || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null)
            throw new IllegalArgumentException("APP_BASE_URL must be an HTTP(S) application URL");
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.db = db;
    }

    void intention(UUID recipient, UUID round, UUID match, String senderName, String topic, boolean invite) {
        String action = invite ? "邀请你沟通家教安排" : "向你发来了家教意向";
        enqueue(recipient, "match_started", round, invite ? "你收到了新的家教邀请" : "你收到了新的家教意向",
            senderName + action + "（" + topic + "）。请到撮合进度查看并回应。", "?view=matches&match=" + match);
    }

    void mutual(UUID initiator, UUID round, UUID match) {
        enqueue(initiator, "match_accepted", round, "对方已接受你的意向",
            "对方已接受你的意向，快去撮合进度查看联系方式。如果尚未完成交换，可在页面确认分享。",
            "?view=matches&match=" + match);
    }

    void matchProgress(UUID recipient, UUID event, UUID match, String type, String title, String content) {
        enqueue(recipient, type, event, title, content, "?view=matches&match=" + match);
    }

    void requestReviewed(UUID owner, UUID submission, UUID request, String title, String rejection) {
        boolean approved = rejection == null;
        enqueue(owner, "request_reviewed", submission, approved ? "你的需求已通过审核" : "你的需求需要修改",
            "《" + title + "》" + (approved ? "已通过审核，老师现在可以浏览并投递。" : "未通过审核。驳回原因：" + rejection),
            "?view=requests&ownedRequest=" + request);
    }

    void verificationReviewed(VerificationService.Entry review) {
        boolean student = "student_id".equals(review.verify_type());
        boolean approved = "approved".equals(review.status());
        String type = student ? "本校学籍" : "技能证书";
        enqueue(review.user_id(), "verification_reviewed", review.id(), type + (approved ? "认证已通过" : "认证需要补充材料"),
            approved ? type + "已核验徽章已点亮。" : "驳回原因：" + review.reject_reason() + "。请核对后重新提交。",
            "?view=profile&section=verifications");
    }

    private void enqueue(UUID recipient, String type, UUID event, String title, String content, String route) {
        db.update("""
            INSERT INTO notifications (id,user_id,event_type,event_id,title,content,target_url,channel)
            VALUES (?,?,?,?,?,?,?,'email') ON CONFLICT (user_id,event_type,event_id,channel) DO NOTHING
            """, UUID.randomUUID(), recipient, type, event, title, content, baseUrl + "/" + route);
    }
}
