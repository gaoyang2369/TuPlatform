package cn.linke;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.text.Normalizer;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
class TeacherReviewService {
    static final String DISPLAY_AVERAGE = "CASE WHEN count(*) >= 3 THEN round(avg(overall_score),1) END";
    private static final String PUBLISHED = "r.reviewee_id=? AND r.reviewer_role='parent' AND r.is_published AND NOT r.is_hidden";
    private final JdbcTemplate db;
    private final ProfileService profiles;
    private final ObjectMapper json;

    TeacherReviewService(JdbcTemplate db, ProfileService profiles, ObjectMapper json) {
        this.db = db;
        this.profiles = profiles;
        this.json = json;
    }

    record Entry(UUID id, String parent_name, String subject, String grade, JsonNode sub_scores,
                 JsonNode tags, String content, String reply_content, Instant created_at) {}

    // Suppress a free-text field if it repeats private location data or contains contact/address details.
    private static final Pattern CONTACT_OR_ADDRESS = Pattern.compile(
        "@|(?<!\\d)(?:\\+?86[ -]?)?1(?:[ -]?\\d){10}(?!\\d)|(?:路|街|巷|弄)\\s*[零〇一二三四五六七八九十百千\\d-]+\\s*号|[\\d一二三四五六七八九十]+\\s*(?:栋|幢|单元|室)");

    private static String publicText(String text, String exactAddress, String legacyAddress) {
        if (text == null || text.isBlank()) return null;
        if (CONTACT_OR_ADDRESS.matcher(Normalizer.normalize(text, Normalizer.Form.NFKC)).find()
            || (exactAddress != null && !exactAddress.isBlank() && text.contains(exactAddress.strip()))
            || (legacyAddress != null && !legacyAddress.isBlank() && text.contains(legacyAddress.strip()))) return null;
        return text;
    }

    private JsonNode parse(String value) {
        try { return json.readTree(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Invalid stored review", e); }
    }

    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> list(String session, UUID teacher, UUID cursor, int limit) {
        profiles.publicTeacher(session, teacher);
        if (limit < 1 || limit > 50) throw ApiError.invalid("limit","每页可读取 1 至 50 条评价");
        var result = new LinkedHashMap<>(db.queryForMap(
            "SELECT count(*) AS published_count, " + DISPLAY_AVERAGE + " AS avg_overall_score FROM reviews r WHERE " + PUBLISHED, teacher));
        var dimensions = new LinkedHashMap<String,Object>();
        db.query("SELECT key, round(avg(value::numeric),1) AS score FROM reviews r " +
            "CROSS JOIN LATERAL jsonb_each_text(r.sub_scores_json) WHERE " + PUBLISHED + " GROUP BY key ORDER BY key",
            rs -> { dimensions.put(rs.getString("key"), rs.getBigDecimal("score")); }, teacher);
        result.put("sub_score_averages", dimensions);
        result.put("top_tags", db.queryForList("SELECT tag,count(*) AS count FROM reviews r " +
            "CROSS JOIN LATERAL jsonb_array_elements_text(r.tags_json) AS tags(tag) WHERE " + PUBLISHED +
            " GROUP BY tag ORDER BY count(*) DESC,tag", teacher));

        String after = cursor == null ? "" : """
             AND (r.created_at,r.id) < (SELECT created_at,id FROM reviews
                 WHERE id=? AND reviewee_id=? AND reviewer_role='parent' AND is_published AND NOT is_hidden)
            """;
        var args = new ArrayList<Object>(List.of(teacher));
        if (cursor != null) { args.add(cursor); args.add(teacher); }
        args.add(limit + 1);
        var rows = db.query("""
            SELECT r.id,r.sub_scores_json,r.tags_json,r.content,r.reply_content,r.created_at,
                   left(trim(n.submission_snapshot->>'publisherDisplayName'),1) AS initial,
                   n.submission_snapshot->>'subject' AS subject,n.submission_snapshot->>'grade' AS grade,
                   s.fields->>'exact_address' AS exact_address,s.fields->>'locationText' AS legacy_address
            FROM reviews r JOIN match_rounds n ON n.id=r.match_id
            LEFT JOIN request_submissions s ON s.id=n.submission_id
            WHERE """ + " " + PUBLISHED + after + " ORDER BY r.created_at DESC,r.id DESC LIMIT ?", (rs,i) -> {
                String initial = rs.getString("initial");
                String name = initial != null && initial.matches("\\p{L}") ? initial + "家长" : "家长";
                String address = rs.getString("exact_address"), legacy = rs.getString("legacy_address");
                return new Entry(rs.getObject("id",UUID.class), name, publicText(rs.getString("subject"),address,legacy),
                    publicText(rs.getString("grade"),address,legacy), parse(rs.getString("sub_scores_json")),
                    parse(rs.getString("tags_json")), publicText(rs.getString("content"),address,legacy),
                    publicText(rs.getString("reply_content"),address,legacy), rs.getTimestamp("created_at").toInstant());
            }, args.toArray());
        result.put("items", rows.stream().limit(limit).toList());
        result.put("nextCursor", rows.size() > limit ? rows.get(limit-1).id() : null);
        return result;
    }
}
