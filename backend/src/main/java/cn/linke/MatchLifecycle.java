package cn.linke;

import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class MatchLifecycle {
    private final JdbcTemplate db;
    private final NotificationService notifications;
    private final TransactionTemplate transactions;

    MatchLifecycle(JdbcTemplate db, NotificationService notifications, PlatformTransactionManager manager) {
        this.db = db;
        this.notifications = notifications;
        this.transactions = new TransactionTemplate(manager);
    }

    private static ApiError changed() {
        return new ApiError(409, "MATCH_STATE_CHANGED", "当前进度不支持此操作，请刷新后核对");
    }

    // Called after locking both participants and their relationship, in the receipt transaction.
    void act(Map<String,Object> row, UUID actor, String action) {
        UUID match = (UUID) row.get("id"), round = (UUID) row.get("current_round_id");
        UUID requester = (UUID) row.get("requester_id"), tutor = (UUID) row.get("tutor_id");
        UUID other = actor.equals(requester) ? tutor : requester;
        UUID cooperation = (UUID) row.get("cooperation_requested_by");
        UUID completion = (UUID) row.get("completion_requested_by");
        boolean exchanged = "MUTUAL".equals(row.get("state")) && row.get("contacts_exchanged_at") != null;
        boolean working = "in_cooperation".equals(row.get("state"));

        switch (action) {
            case "request-cooperation", "confirm-cooperation" -> {
                if (!exchanged || actor.equals(cooperation)) throw changed();
                if (cooperation != null) confirmCooperation(round);
                else {
                    if (!"request-cooperation".equals(action)) throw changed();
                    db.update("UPDATE match_rounds SET cooperation_requested_by=?, cooperation_requested_at=now() WHERE id=?", actor, round);
                    notifications.matchProgress(other, UUID.randomUUID(), match, "cooperation_requested", "请确认试课或合作",
                        "对方已标记开始合作。请确认，或选择沟通后暂未合作。3 天内未提出异议将自动确认。");
                }
            }
            case "close-no-deal" -> {
                if (!exchanged) throw changed();
                db.update("UPDATE match_rounds SET state='closed_no_deal', cooperation_requested_by=NULL, cooperation_requested_at=NULL WHERE id=?", round);
            }
            case "request-completion" -> {
                if (!working || completion != null) throw changed();
                db.update("UPDATE match_rounds SET completion_requested_by=?, completion_requested_at=now() WHERE id=?", actor, round);
                notifications.matchProgress(other, UUID.randomUUID(), match, "completion_requested", "请确认本阶段是否完结",
                    "对方已标记试课或合作阶段完结。请确认，或选择仍在合作。3 天内未提出异议将自动完结。");
            }
            case "confirm-completion", "continue-cooperation" -> {
                if (!working || !other.equals(completion)) throw changed();
                if ("confirm-completion".equals(action)) complete(round, match, requester, tutor);
                else db.update("UPDATE match_rounds SET completion_requested_by=NULL, completion_requested_at=NULL WHERE id=?", round);
            }
            default -> throw ApiError.invalid("action", "请选择有效操作");
        }
        bumpVersion(match);
    }

    private void bumpVersion(UUID match) {
        db.update("UPDATE match_relationships SET version=version+1 WHERE id=?", match);
    }

    private void confirmCooperation(UUID round) {
        db.update("UPDATE match_rounds SET state='in_cooperation', cooperation_confirmed_at=now() WHERE id=?", round);
    }

    private void complete(UUID round, UUID match, UUID requester, UUID tutor) {
        db.update("""
            UPDATE match_rounds SET state='completed', completed_at=now(), review_deadline_at=now()+interval '336 hours'
            WHERE id=?
            """, round);
        for (UUID recipient : new UUID[]{requester, tutor}) {
            notifications.matchProgress(recipient, round, match, "match_completed", "合作已完结，互评资格已解锁",
                "本阶段合作已完结，互评期限为 14 天。请前往撮合进度查看截止时间。");
        }
    }

    private static final String DUE = """
        (n.state='MUTUAL' AND n.cooperation_requested_at <= now()-interval '72 hours')
        OR (n.state='in_cooperation' AND n.completion_requested_at <= now()-interval '72 hours')
        OR (n.state='MUTUAL' AND n.cooperation_requested_at IS NULL AND n.followup_reminded_at IS NULL
            AND n.contacts_exchanged_at <= now()-interval '120 hours')
        """;
    private static final String CURRENT = " FROM match_relationships m JOIN match_rounds n ON n.id=m.current_round_id ";

    @Scheduled(cron="${app.match-followup-cron:0 * * * * *}")
    void followUp() {
        var candidates = db.queryForList("SELECT m.id,m.requester_id,m.tutor_id" + CURRENT +
            "WHERE " + DUE + " ORDER BY m.id LIMIT 200");
        for (var row : candidates) {
            UUID match = (UUID) row.get("id"), requester = (UUID) row.get("requester_id"), tutor = (UUID) row.get("tutor_id");
            transactions.executeWithoutResult(tx -> {
                db.queryForList("SELECT id FROM accounts WHERE id IN (?,?) ORDER BY id FOR UPDATE", requester, tutor);
                var due = db.queryForList("SELECT n.id,n.state,n.cooperation_requested_at" + CURRENT +
                    "WHERE m.id=? AND (" + DUE + ") FOR UPDATE OF m", match);
                if (due.isEmpty()) return;
                var current = due.getFirst();
                UUID round = (UUID) current.get("id");
                if ("in_cooperation".equals(current.get("state"))) {
                    complete(round, match, requester, tutor);
                } else if (current.get("cooperation_requested_at") != null) {
                    confirmCooperation(round);
                } else {
                    db.update("UPDATE match_rounds SET followup_reminded_at=now() WHERE id=?", round);
                    for (UUID recipient : new UUID[]{requester, tutor}) {
                        notifications.matchProgress(recipient, round, match, "match_followup", "试课或合作有进展吗？",
                            "交换联系方式已有 5 天。若已约好试课，可以记录开始合作；暂未合作也可以结束本次联系。");
                    }
                    return;
                }
                bumpVersion(match);
            });
        }
    }
}
