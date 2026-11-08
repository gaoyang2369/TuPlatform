package cn.linke;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class ProfileService {
    private final JdbcTemplate db;
    private final AuthService auth;
    private final Secrets secrets;
    private final ObjectMapper json;
    private final Set<String> categories;
    ProfileService(JdbcTemplate db, AuthService auth, Secrets secrets, ObjectMapper json,
        @Value("${app.teacher-categories}") String categories) {
        this.db = db; this.auth = auth; this.secrets = secrets; this.json = json;
        this.categories = Arrays.stream(categories.split(",")).map(String::strip).filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet());
        if (!Rules.CATEGORIES.containsAll(this.categories)) throw new IllegalArgumentException("Unknown TEACHER_CATEGORIES");
    }
    Map<String, Object> options() { return Map.of("categoryCodes", categories.stream().sorted().toList(), "serviceModes", List.of("ONLINE", "OFFLINE"), "areaCodes", Rules.AREAS.stream().sorted().toList()); }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException("JSON encoding failed", e); }
    }
    private List<String> values(Object value) {
        try { return json.readValue(value.toString(), new TypeReference<List<String>>() {}); }
        catch (Exception e) { throw new IllegalStateException("Stored profile invalid", e); }
    }
    private String amount(Object value) { return value == null ? null : ((BigDecimal) value).toPlainString(); }
    private Profile read(Account a) {
        Map<String, Object> p = db.queryForMap("SELECT * FROM profiles WHERE account_id=?", a.id());
        Map<String, Object> teacher = null;
        if ("TUTOR".equals(a.identity())) {
            teacher = new LinkedHashMap<>();
            teacher.put("categoryCodes", values(p.get("category_codes"))); teacher.put("serviceModes", values(p.get("service_modes")));
            teacher.put("areaCodes", values(p.get("area_codes"))); teacher.put("introduction", p.get("introduction"));
            teacher.put("priceMin", amount(p.get("price_min"))); teacher.put("priceMax", amount(p.get("price_max")));
            teacher.put("availabilityText", p.get("availability_text")); teacher.put("public", p.get("is_public")); teacher.put("version", p.get("version"));
        }
        return new Profile((String) p.get("display_name"), ((Number) p.get("version")).longValue(), teacher);
    }
    private void version(Account a, Long expected) {
        if (expected == null || expected < 0) throw ApiError.invalid("expectedVersion", "请先读取当前资料");
        if (a.profileVersion() != expected) throw new ApiError(409, "VERSION_CONFLICT", "资料已在其他页面修改，请读取最新资料后再保存；当前输入仍保留");
    }
    public Profile get(String session) { return read(auth.business(session, null)); }
    public Profile saveBase(String session, BaseProfileBody body) {
        Account a = auth.business(session, "REQUESTER"); version(a, body.expectedVersion());
        String name = Rules.text("displayName", body.displayName(), 30, true);
        db.update("UPDATE profiles SET display_name=?,version=version+1,updated_at=now() WHERE account_id=?", name, a.id());
        return read(a);
    }
    public Profile saveTeacher(String session, TeacherBody body) {
        Account a = auth.business(session, "TUTOR"); version(a, body.expectedVersion());
        boolean published = Boolean.TRUE.equals(read(a).teacher().get("public"));
        validateTeacher(body, published);
        db.update("""
            UPDATE profiles SET display_name=?,category_codes=CAST(? AS jsonb),service_modes=CAST(? AS jsonb),area_codes=CAST(? AS jsonb),
              introduction=?,price_min=?,price_max=?,availability_text=?,version=version+1,updated_at=now() WHERE account_id=?
            """, body.displayName() == null ? "" : body.displayName(), encode(body.categoryCodes() == null ? List.of() : body.categoryCodes()),
            encode(body.serviceModes() == null ? List.of() : body.serviceModes()), encode(body.areaCodes() == null ? List.of() : body.areaCodes()),
            body.introduction() == null ? "" : body.introduction(), Rules.money("priceMin", body.priceMin()), Rules.money("priceMax", body.priceMax()),
            body.availabilityText() == null ? "" : body.availabilityText(), a.id());
        return read(a);
    }
    void validateTeacher(TeacherBody body, boolean complete) {
        Rules.text("displayName", body.displayName(), 30, complete);
        Rules.text("introduction", body.introduction(), 1000, complete);
        Rules.text("availabilityText", body.availabilityText(), 300, false);
        Rules.choices("categoryCodes", body.categoryCodes(), categories, complete);
        List<String> modes = Rules.choices("serviceModes", body.serviceModes(), Rules.MODES, complete);
        Rules.choices("areaCodes", body.areaCodes(), Rules.AREAS, complete && modes.contains("OFFLINE"));
        Rules.priceRange(Rules.money("priceMin", body.priceMin()), Rules.money("priceMax", body.priceMax()));
    }
    public Profile visibility(String session, VersionBody body, boolean publish, String key) {
        Account a = auth.business(session, "TUTOR");
        if (key == null || !key.matches("[A-Za-z0-9_-]{16,100}")) throw ApiError.invalid("idempotencyKey", "请提供有效的重复提交凭据");
        String action = publish ? "publish" : "unpublish";
        String requestHash = secrets.digest(encode(body));
        List<Map<String, Object>> existing = db.queryForList("SELECT request_hash,response_json FROM profile_mutation_receipts WHERE account_id=? AND action=? AND idempotency_key=?", a.id(), action, key);
        if (!existing.isEmpty()) {
            var receipt = existing.getFirst();
            if (!requestHash.equals(receipt.get("request_hash"))) throw new ApiError(409, "IDEMPOTENCY_CONFLICT", "同一操作凭据不能用于不同内容");
            try { return json.readValue(receipt.get("response_json").toString(), Profile.class); }
            catch (Exception e) { throw new IllegalStateException("Stored receipt invalid", e); }
        }
        version(a, body.expectedVersion());
        Profile p = read(a);
        if (publish) {
            Map<String, Object> t = p.teacher();
            validateTeacher(new TeacherBody(p.version(), p.displayName(), values(encode(t.get("categoryCodes"))),
                values(encode(t.get("serviceModes"))), values(encode(t.get("areaCodes"))), (String) t.get("introduction"),
                (String) t.get("priceMin"), (String) t.get("priceMax"), (String) t.get("availabilityText")), true);
        }
        if (!Boolean.valueOf(publish).equals(p.teacher().get("public"))) {
            db.update("UPDATE profiles SET is_public=?,version=version+1,updated_at=now() WHERE account_id=?", publish, a.id());
        }
        Profile result = read(a);
        db.update("INSERT INTO profile_mutation_receipts(account_id,action,idempotency_key,request_hash,response_json) VALUES (?,?,?,?,CAST(? AS jsonb))", a.id(), action, key, requestHash, encode(result));
        return result;
    }
    // Allowlisted public projection only; caller must be a verified, signed-in requester.
    public Map<String, Object> publicTeacher(String session, UUID id) {
        auth.business(session, "REQUESTER");
        List<Map<String, Object>> rows = db.queryForList("""
            SELECT a.id,p.version,p.display_name,p.category_codes,p.service_modes,p.area_codes,p.introduction,p.price_min,p.price_max,p.availability_text
            FROM profiles p JOIN accounts a ON a.id=p.account_id
            WHERE a.id=? AND a.email_verified AND a.business_identity='TUTOR' AND p.is_public
            """, id);
        if (rows.isEmpty()) throw new ApiError(404, "NOT_FOUND", "老师资料当前不可查看");
        var r = rows.getFirst(); var result = new LinkedHashMap<String, Object>();
        result.put("id", r.get("id")); result.put("profileVersion", r.get("version")); result.put("displayName", r.get("display_name"));
        result.put("categoryCodes", values(r.get("category_codes"))); result.put("serviceModes", values(r.get("service_modes"))); result.put("areaCodes", values(r.get("area_codes")));
        result.put("introduction", r.get("introduction")); result.put("priceMin", amount(r.get("price_min"))); result.put("priceMax", amount(r.get("price_max"))); result.put("availabilityText", r.get("availability_text"));
        return result;
    }
}
