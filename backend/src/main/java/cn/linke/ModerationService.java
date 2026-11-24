package cn.linke;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class ModerationService {
    private final JdbcTemplate db;
    private final AuthService auth;
    ModerationService(JdbcTemplate db, AuthService auth) { this.db = db; this.auth = auth; }

    private void page(int offset) {
        if (offset < 0) throw ApiError.invalid("offset", "列表位置无效");
    }

    List<Map<String,Object>> users(String q, String role, int offset) {
        page(offset);
        String term = Rules.text("q", q, 254, false).strip();
        if (!Set.of("", "REQUESTER", "TUTOR", "ADMIN").contains(role)) throw ApiError.invalid("role", "请选择有效角色");
        return db.queryForList("""
            SELECT a.id,a.account,a.email,a.business_identity,a.operator_granted,a.is_frozen,a.email_verified,
                   p.display_name,p.avatar_url,
                   EXISTS (SELECT 1 FROM verifications WHERE user_id=a.id AND status='approved' AND verify_type='student_id') AS school_verified,
                   EXISTS (SELECT 1 FROM verifications WHERE user_id=a.id AND status='approved' AND verify_type='skill_cert') AS skill_verified,
                   (SELECT count(*) FROM service_requests r WHERE owner_id=a.id AND EXISTS
                      (SELECT 1 FROM request_submissions s WHERE s.request_id=r.id AND s.status='APPROVED')) AS demand_count
            FROM accounts a JOIN profiles p ON p.account_id=a.id
            WHERE (position(lower(?) in lower(concat_ws(' ',a.account,p.display_name,a.email)))>0)
              AND (?='' OR a.business_identity=? OR (?='ADMIN' AND a.operator_granted))
            ORDER BY a.created_at DESC,a.id LIMIT 20 OFFSET ?
            """, term, role, role, role, offset);
    }

    List<Map<String,Object>> reviews(UUID author, int offset) {
        page(offset);
        return db.queryForList("""
            SELECT id,content,overall_score,created_at,is_hidden,hidden_reason
            FROM reviews WHERE reviewer_id=? AND is_published ORDER BY created_at DESC,id LIMIT 20 OFFSET ?
            """, author, offset);
    }

    void userAction(String session, UUID target, String action, String reason) {
        var admin = auth.admin(session, true);
        reason = Rules.text("reason", reason, 300, true).strip();
        var users = db.queryForList("SELECT id FROM accounts WHERE id=? FOR UPDATE", UUID.class, target);
        if (users.isEmpty()) throw new ApiError(404, "NOT_FOUND", "用户不存在");
        int changed;
        if ("reset-avatar".equals(action)) {
            changed = db.update("UPDATE profiles SET avatar_url=NULL,version=version+1,updated_at=now() WHERE account_id=? AND avatar_url IS NOT NULL", target);
        } else {
            if (!Set.of("freeze", "unfreeze").contains(action)) throw ApiError.invalid("action", "无效操作");
            if (target.equals(admin.id())) throw new ApiError(403, "ADMIN_PROTECTED", "不能冻结当前登录账号");
            boolean freeze = "freeze".equals(action);
            changed = db.update("UPDATE accounts SET is_frozen=? WHERE id=? AND is_frozen<>?", freeze, target, freeze);
            if (freeze) db.update("DELETE FROM auth_sessions WHERE account_id=?", target);
        }
        if (changed == 0) throw new ApiError(409, "STATE_CHANGED", "状态已变化，请刷新用户列表");
        AdminLog.record(db, admin.id(), action, "user", target, reason);
    }

    void hide(String session, UUID review, String reason) {
        var admin = auth.admin(session, true);
        reason = Rules.text("reason", reason, 300, true).strip();
        if (db.update("UPDATE reviews SET is_hidden=true,hidden_reason=? WHERE id=? AND is_published AND NOT is_hidden", reason, review) != 1)
            throw new ApiError(409, "STATE_CHANGED", "评价已隐藏或尚未公开，请刷新列表");
        AdminLog.record(db, admin.id(), "hide", "review", review, reason);
    }
}
