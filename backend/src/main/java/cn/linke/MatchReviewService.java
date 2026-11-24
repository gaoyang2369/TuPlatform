package cn.linke;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class MatchReviewService {
    record Submission(UUID roundId, JsonNode subScores, List<String> tags, String content) {}
    record Reply(String content) {}
    record Form(List<String> dimensions, List<String> tags) {}
    record Entry(UUID id, UUID roundId, String reviewerRole, boolean mine, int overallScore, JsonNode subScores,
                 JsonNode tags, String content, String replyContent, Instant repliedAt, boolean published,
                 Instant revealAt, Instant createdAt) {}
    record Page(UUID roundId, boolean canSubmit, Form form, List<Entry> reviews) {}

    private static final Form PARENT = new Form(List.of("守时履约","讲解清晰度","备课用心度","孩子接受度"),
        List.of("守时靠谱","很有耐心","讲题清晰","孩子很喜欢","备课认真"));
    private static final Form TEACHER = new Form(List.of("需求描述真实","沟通尊重顺畅","课时费结算及时"),
        List.of("需求真实","沟通顺畅","尊重老师","结算准时"));
    private final JdbcTemplate db;
    private final AuthService auth;
    private final ObjectMapper json;
    private final NotificationService notifications;

    MatchReviewService(JdbcTemplate db, AuthService auth, ObjectMapper json, NotificationService notifications) {
        this.db = db;
        this.auth = auth;
        this.json = json;
        this.notifications = notifications;
    }

    private record Deal(UUID round, UUID other, boolean parent, boolean completed, boolean withinWindow) {
        Form form() { return parent ? PARENT : TEACHER; }
        String role() { return parent ? "parent" : "teacher"; }
    }

    private Deal deal(UUID match, UUID actor) {
        var rows = db.query("""
            SELECT n.id,m.requester_id,m.tutor_id,n.state,n.review_deadline_at >= clock_timestamp() AS within_window
            FROM match_relationships m JOIN match_rounds n ON n.id=m.current_round_id
            WHERE m.id=? AND ? IN (m.requester_id,m.tutor_id)
            """, (rs,i) -> {
                UUID parent = rs.getObject("requester_id",UUID.class), tutor = rs.getObject("tutor_id",UUID.class);
                return new Deal(rs.getObject("id",UUID.class), actor.equals(parent) ? tutor : parent,
                    actor.equals(parent), "completed".equals(rs.getString("state")), rs.getBoolean("within_window"));
            }, match, actor);
        if (rows.isEmpty()) throw unavailable();
        return rows.getFirst();
    }

    private static ApiError unavailable() { return new ApiError(404,"NOT_FOUND","记录不存在或不可查看"); }
    private static ApiError conflict(String message) { return new ApiError(409,"REVIEW_CONFLICT",message); }

    @Scheduled(cron="${app.review-reveal-cron:0 * * * * *}")
    public void publishExpiredReviews() {
        db.update("UPDATE reviews SET is_published=true WHERE NOT is_published AND reveal_at<=now()");
    }

    public Page list(String session, UUID match) {
        UUID actor = auth.current(session,false).id();
        Deal deal = deal(match,actor);
        db.update("""
            UPDATE reviews r SET is_published=true FROM match_rounds n
            WHERE n.match_id=? AND n.id=r.match_id AND NOT r.is_published AND r.reveal_at<=clock_timestamp()
            """, match);
        var entries = db.query("""
            SELECT r.* FROM reviews r JOIN match_rounds n ON n.id=r.match_id
            WHERE n.match_id=? AND (r.is_published OR r.reviewer_id=?) ORDER BY n.initiated_at DESC,r.created_at,r.id
            """, (rs,i) -> {
            var replied = rs.getTimestamp("replied_at");
            return new Entry(rs.getObject("id",UUID.class),rs.getObject("match_id",UUID.class),
                rs.getString("reviewer_role"),actor.equals(rs.getObject("reviewer_id",UUID.class)),rs.getInt("overall_score"),
                parse(rs.getString("sub_scores_json")),parse(rs.getString("tags_json")),rs.getString("content"),
                rs.getString("reply_content"),replied == null ? null : replied.toInstant(),rs.getBoolean("is_published"),
                rs.getTimestamp("reveal_at").toInstant(),rs.getTimestamp("created_at").toInstant());
        },match,actor);
        boolean mine = entries.stream().anyMatch(e -> e.roundId().equals(deal.round()) && e.mine());
        return new Page(deal.round(),deal.completed() && deal.withinWindow() && !mine,deal.form(),entries);
    }

    private JsonNode parse(String value) {
        try { return json.readTree(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Invalid stored review",e); }
    }

    private static String limitedText(String value, int limit) {
        String text = value == null ? "" : value.strip();
        if (text.codePointCount(0,text.length()) > limit || text.indexOf(0) >= 0)
            throw ApiError.invalid("content","请填写 " + limit + " 字以内的有效文字");
        return text;
    }

    private static int overall(Form form, JsonNode scores) {
        if (scores == null || !scores.isObject() || scores.size()!=form.dimensions().size())
            throw ApiError.invalid("subScores","请为每个维度评 1 至 5 星");
        int sum = 0;
        for (String dimension : form.dimensions()) {
            JsonNode score = scores.path(dimension);
            if (!score.isIntegralNumber() || !score.canConvertToInt() || score.intValue()<1 || score.intValue()>5)
                throw ApiError.invalid("subScores",dimension + "需要 1 至 5 的整数评分");
            sum += score.intValue();
        }
        return Math.round((float)sum / scores.size());
    }

    private static void validateKey(String key) {
        if (key!=null && !key.matches("[A-Za-z0-9_-]{16,100}"))
            throw ApiError.invalid("idempotencyKey","请提供有效的重复提交凭据");
    }

    public void submit(String session, UUID match, Submission body, String key) {
        validateKey(key);
        UUID actor = auth.current(session,false).id();
        // Serialize both writers and re-read the current round and wall clock after acquiring the lock.
        Deal initial = deal(match,actor);
        db.queryForList("SELECT id FROM accounts WHERE id IN (?,?) ORDER BY id FOR UPDATE",actor,initial.other());
        auth.current(session,false);
        db.queryForList("SELECT id FROM match_relationships WHERE id=? FOR UPDATE",match);
        Deal deal = deal(match,actor);
        if (!deal.completed() || (body.roundId()!=null && !deal.round().equals(body.roundId())))
            throw conflict("请在对应合作完结后评价");
        int overall = overall(deal.form(),body.subScores());
        List<String> tags = body.tags()==null ? List.of() : body.tags();
        if (tags.size()>4 || new HashSet<>(tags).size()!=tags.size() || tags.stream().anyMatch(t -> t==null || !deal.form().tags().contains(t)))
            throw ApiError.invalid("tags","请最多选择 4 个不同的预设标签");
        String content = limitedText(body.content(),300);
        String scores = body.subScores().toString(), tagJson = json.valueToTree(tags).toString();
        var existing = db.queryForList("""
            SELECT (submission_key=? AND sub_scores_json=?::jsonb AND tags_json=?::jsonb AND content=?) AS replay
            FROM reviews WHERE match_id=? AND reviewer_role=?
            """,key,scores,tagJson,content,deal.round(),deal.role());
        if (!existing.isEmpty()) {
            if (Boolean.TRUE.equals(existing.getFirst().get("replay"))) return;
            throw conflict("你已提交本轮评价");
        }
        int inserted = db.update("""
            INSERT INTO reviews(id,match_id,reviewer_id,reviewee_id,reviewer_role,overall_score,sub_scores_json,tags_json,content,reveal_at,submission_key)
            SELECT ?,id,?,?,?,?,?::jsonb,?::jsonb,?,review_deadline_at,? FROM match_rounds
            WHERE id=? AND review_deadline_at>=clock_timestamp()
            """,UUID.randomUUID(),actor,deal.other(),deal.role(),overall,scores,tagJson,content,key,deal.round());
        if (inserted==0) throw conflict("14 天评价窗口已结束");
        boolean both = Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM reviews WHERE match_id=? AND reviewer_id=?)",
            Boolean.class,deal.round(),deal.other()));
        if (both) {
            db.update("UPDATE reviews SET is_published=true WHERE match_id=?",deal.round());
            for (UUID recipient : List.of(actor,deal.other())) notifications.matchProgress(recipient,deal.round(),match,"reviews_revealed",
                "互评已同时公开，可前往查看","双方都已提交评价。请到撮合进度查看，你可以回复收到的评价一次。");
        } else {
            notifications.matchProgress(deal.other(),deal.round(),match,"review_submitted","对方已留下评价",
                "你也可以在 14 天内提交真实反馈，双方提交或到期后将同时公开。");
        }
    }

    public void reply(String session, UUID id, Reply body, String key) {
        validateKey(key);
        UUID actor = auth.current(session,false).id();
        var rows = db.queryForList("SELECT reply_content,reply_key FROM reviews WHERE id=? AND reviewee_id=? AND is_published FOR UPDATE",id,actor);
        if (rows.isEmpty()) throw unavailable();
        String content = limitedText(body.content(),200);
        if (content.isBlank()) throw ApiError.invalid("content","请填写回复内容");
        var row = rows.getFirst();
        if (row.get("reply_content")!=null) {
            if (key!=null && key.equals(row.get("reply_key")) && content.equals(row.get("reply_content"))) return;
            throw conflict("每条评价只可回复一次");
        }
        db.update("UPDATE reviews SET reply_content=?,replied_at=clock_timestamp(),reply_key=? WHERE id=?",content,key,id);
    }
}
