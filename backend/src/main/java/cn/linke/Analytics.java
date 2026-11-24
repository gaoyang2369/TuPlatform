package cn.linke;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
class Analytics {
    private final JdbcTemplate db;
    private final String fixtureBatch;
    Analytics(JdbcTemplate db, @org.springframework.beans.factory.annotation.Value("${app.analytics-test-mode:false}") boolean testMode,
              org.springframework.core.env.Environment environment) {
        if (testMode && environment.matchesProfiles("prod"))
            throw new IllegalArgumentException("生产环境须关闭测试埋点标记");
        this.db = db;
        this.fixtureBatch = testMode ? "linke-demo-20261111-v1" : null;
    }

    void record(String event, UUID actor, UUID session) {
        db.update("""
            INSERT INTO analytics_events(event_name,user_id,session_id,meta_json)
            VALUES (?,?,?,jsonb_strip_nulls(jsonb_build_object('fixture_batch',?::text)))
            """,event,actor,session,fixtureBatch);
    }

    void authenticated(String event, UUID actor, String token) {
        // Domain-separated pseudonym. Never store the login token or its authentication hash.
        record(event, actor, sessionId(token));
    }

    void confirmed(UUID responder, String token, UUID initiator) {
        db.update("""
            INSERT INTO analytics_events(event_name,user_id,session_id,meta_json)
            VALUES ('match_confirmed',?,?,jsonb_strip_nulls(jsonb_build_object('initiator',?::text,'fixture_batch',?::text)))
            """, responder, sessionId(token), initiator, fixtureBatch);
    }

    private UUID sessionId(String token) {
        return UUID.nameUUIDFromBytes(("analytics:" + token).getBytes(StandardCharsets.UTF_8));
    }

    record Step(String name, long visitors, long advanced) {}
    List<Step> funnel(int days) {
        if (days != 7 && days != 30) throw ApiError.invalid("days","请选择近 7 天或近 30 天");
        return db.query("""
            WITH steps(n,name) AS (VALUES (1,'page_view_teachers'),(2,'open_teacher_detail'),
                (3,'send_match_intent'),(4,'match_confirmed'),(5,'view_contact')),
            visits AS (
                SELECT n, coalesce(meta_json->>'initiator',user_id::text,'guest:' || session_id::text) visitor,
                       min(created_at) first_visit, max(created_at) last_visit
                FROM analytics_events JOIN steps ON name=event_name
                WHERE created_at >= now() - ? * interval '1 day' GROUP BY 1,2)
            SELECT s.name, count(v.visitor), count(p.visitor)
            FROM steps s LEFT JOIN visits v ON v.n=s.n
            LEFT JOIN visits p ON p.n=s.n-1 AND p.visitor=v.visitor AND p.first_visit <= v.last_visit
            GROUP BY s.n,s.name ORDER BY s.n
            """, (r,i) -> new Step(r.getString(1),r.getLong(2),r.getLong(3)), days);
    }
}
