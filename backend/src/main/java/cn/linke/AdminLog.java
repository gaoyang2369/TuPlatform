package cn.linke;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

final class AdminLog {
    private AdminLog() {}

    static void record(JdbcTemplate db, UUID admin, String action, String type, UUID target, String reason) {
        db.update("INSERT INTO admin_logs(id,admin_id,action_type,target_type,target_id,reason) VALUES (?,?,?,?,?,?)",
            UUID.randomUUID(), admin, action, type, target, reason);
    }
}
