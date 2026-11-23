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
    private static final String TRUST_FIELDS = """
        EXISTS (SELECT 1 FROM verifications v WHERE v.user_id=p.account_id AND v.verify_type='student_id' AND v.status='approved') AS is_school_verified,
        EXISTS (SELECT 1 FROM verifications v WHERE v.user_id=p.account_id AND v.verify_type='skill_cert' AND v.status='approved') AS is_skill_verified
        """;
    private static final String TEACHER_QUERY = "SELECT a.id,p.*," + TRUST_FIELDS + " FROM profiles p JOIN accounts a ON a.id=p.account_id WHERE ";
    private final JdbcTemplate db;
    private final AuthService auth;
    private final Secrets secrets;
    private final ObjectMapper json;
    private final Set<String> categories;
    private final String pilotSchool;
    ProfileService(JdbcTemplate db, AuthService auth, Secrets secrets, ObjectMapper json,
        @Value("${app.teacher-categories}") String categories, @Value("${app.pilot-school}") String pilotSchool) {
        this.pilotSchool = pilotSchool;
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
        Map<String, Object> p = db.queryForMap("SELECT p.*," + TRUST_FIELDS + " FROM profiles p WHERE account_id=?", a.id());
        Map<String, Object> teacher = null;
        if ("TUTOR".equals(a.identity())) {
            teacher = new LinkedHashMap<>();
            addVerificationFlags(teacher, p);
            addResumeFields(teacher, p, true);
            teacher.put("categoryCodes", values(p.get("category_codes"))); teacher.put("serviceModes", values(p.get("service_modes")));
            teacher.put("areaCodes", values(p.get("area_codes"))); teacher.put("introduction", p.get("introduction"));
            teacher.put("priceMin", amount(p.get("price_min"))); teacher.put("priceMax", amount(p.get("price_max")));
            teacher.put("availabilityText", p.get("availability_text")); teacher.put("public", p.get("is_public")); teacher.put("version", p.get("version"));
        }
        return new Profile((String) p.get("display_name"), (String) p.get("avatar_url"), ((Number) p.get("version")).longValue(), teacher);
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
        validateResume(body);
        db.update("""
            UPDATE profiles SET display_name=?,category_codes=CAST(? AS jsonb),service_modes=CAST(? AS jsonb),area_codes=CAST(? AS jsonb),
              introduction=?,price_min=?,price_max=?,availability_text=?,school_name=?,college_major=?,grade_level=?,
              teach_grades=?::jsonb,teach_subjects=?::jsonb,years_exp=?,teaching_style_tags=?::jsonb,cases_json=?::jsonb,
              trial_policy=?,version=version+1,updated_at=now() WHERE account_id=?
            """, body.displayName() == null ? "" : body.displayName(), encode(body.categoryCodes() == null ? List.of() : body.categoryCodes()),
            encode(body.serviceModes() == null ? List.of() : body.serviceModes()), encode(body.areaCodes() == null ? List.of() : body.areaCodes()),
            body.introduction() == null ? "" : body.introduction(), Rules.money("priceMin", body.priceMin()), Rules.money("priceMax", body.priceMax()),
            body.availabilityText() == null ? "" : body.availabilityText(),
            body.schoolName() == null || body.schoolName().isBlank() ? pilotSchool : body.schoolName(), body.collegeMajor(),
            body.gradeLevel(), encode(body.teachGrades()), encode(body.teachSubjects()), body.yearsExp(),
            encode(body.teachingStyleTags() == null ? List.of() : body.teachingStyleTags()), encode(body.casesJson()), body.trialPolicy(), a.id());
        return read(a);
    }
    private TeacherBody storedBody(String name, Map<String, Object> fields) {
        var input = new LinkedHashMap<>(fields);
        input.put("displayName", name);
        for (String key : List.of("id", "public", "version", "profileVersion", "avatar_url", "resumePdfUrl", "resumePdfName",
                "is_school_verified", "is_skill_verified"))
            input.remove(key);
        return json.convertValue(input, TeacherBody.class);
    }
    private void addResumeFields(Map<String, Object> result, Map<String, Object> row, boolean editing) {
        List<String> grades = values(row.get("teach_grades"));
        // Legacy public profiles may have no school on record. The editor offers the pilot default.
        boolean useDefault = row.get("school_name") == null && (editing || !grades.isEmpty());
        result.put("schoolName", useDefault ? pilotSchool : row.get("school_name"));
        result.put("collegeMajor", row.get("college_major"));
        result.put("gradeLevel", row.get("grade_level"));
        result.put("teachGrades", grades);
        result.put("teachSubjects", values(row.get("teach_subjects")));
        result.put("yearsExp", row.get("years_exp"));
        result.put("teachingStyleTags", values(row.get("teaching_style_tags")));
        try {
            result.put("casesJson", json.readValue(row.get("cases_json").toString(), new TypeReference<List<TeachingCase>>() {}));
        } catch (Exception e) { throw new IllegalStateException("Invalid stored teaching cases", e); }
        result.put("trialPolicy", row.get("trial_policy"));
        result.put("resumePdfUrl", row.get("resume_pdf_url"));
        result.put("resumePdfName", row.get("resume_pdf_name"));
    }
    static final Set<String> STYLES = Set.of("耐心细致", "擅长基础巩固", "习惯培养", "善于引导思考", "备课认真");
    static final Set<String> POLICIES = Set.of("支持线上免费沟通15分钟", "支持首次试课半价", "按正常时薪试课", "暂不支持试课");
    static void validateResume(TeacherBody body) {
        Rules.text("schoolName", body.schoolName(), 100, false);
        Rules.text("collegeMajor", body.collegeMajor(), 100, false);
        Rules.text("gradeLevel", body.gradeLevel(), 30, false);
        Rules.text("yearsExp", body.yearsExp(), 80, false);
        requiredLabels("teachGrades", body.teachGrades());
        requiredLabels("teachSubjects", body.teachSubjects());
        if (Rules.choices("teachingStyleTags", body.teachingStyleTags(), STYLES, false).size() > 3)
            throw ApiError.invalid("teachingStyleTags", "最多选择三个教学风格标签");
        if (body.trialPolicy() == null || !POLICIES.contains(body.trialPolicy()))
            throw ApiError.invalid("trialPolicy", "请选择首次试课政策");
        if (body.casesJson() == null || body.casesJson().isEmpty() || body.casesJson().size() > 3)
            throw ApiError.invalid("casesJson", "请填写一至三条辅导案例");
        for (TeachingCase item : body.casesJson()) {
            if (item == null) throw ApiError.invalid("casesJson", "请填写完整案例");
            Rules.text("casesJson", item.studentGrade(), 40, true);
            Rules.text("casesJson", item.focus(), 300, true);
            Rules.text("casesJson", item.outcome(), 300, true);
        }
    }
    private static void requiredLabels(String field, List<String> labels) {
        if (labels == null || labels.isEmpty() || labels.size() > 32)
            throw ApiError.invalid(field, "请填写 1 至 32 项");
        for (String label : labels) Rules.text(field, label, 40, true);
        if (labels.stream().map(String::strip).distinct().count() != labels.size())
            throw ApiError.invalid(field, "请移除重复项");
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
            TeacherBody stored = storedBody(p.displayName(), t);
            validateTeacher(stored, true);
            validateResume(stored);
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
        return teacherProjection(id);
    }
    static UUID pageCursor(String cursor) {
        if (cursor==null || cursor.isBlank()) return new UUID(-1,-1);
        try { return UUID.fromString(cursor); }
        catch (IllegalArgumentException e) { throw ApiError.invalid("cursor","列表位置已失效，请重新读取"); }
    }
    public Map<String,Object> publicTeachers(String session,String cursor,String category,String area,String mode) {
        auth.business(session,"REQUESTER");
        String condition="a.email_verified AND a.business_identity='TUTOR' AND p.is_public AND a.id<?";
        var args=new java.util.ArrayList<Object>();args.add(pageCursor(cursor));
        String[][] filters={{"category_codes",category},{"area_codes",area},{"service_modes",mode}};
        for (var filter:filters) if(filter[1]!=null&&!filter[1].isBlank()) {
            var allowed=switch(filter[0]){case "category_codes"->Rules.CATEGORIES;case "area_codes"->Rules.AREAS;default->Rules.MODES;};
            if(!allowed.contains(filter[1]))throw ApiError.invalid("filter","请选择有效的筛选条件");
            condition+=" AND p."+filter[0]+" @> CAST(? AS jsonb)";args.add(encode(List.of(filter[1])));
        }
        var rows=db.queryForList(TEACHER_QUERY+condition+" ORDER BY a.id DESC LIMIT 11",args.toArray());
        var result=new LinkedHashMap<String,Object>();result.put("items",rows.stream().limit(10).map(this::teacherProjection).toList());
        result.put("nextCursor",rows.size()>10?rows.get(9).get("id").toString():null);return result;
    }
    Map<String,Object> matchTeacher(UUID id,long expectedVersion) {
        var teacher=teacherProjectionForMatch(id);
        if(((Number)teacher.get("profileVersion")).longValue()!=expectedVersion)throw new ApiError(409,"TEACHER_PROFILE_CHANGED","老师资料已变化，请重新读取并核对");
        validateTeacher(storedBody((String) teacher.get("displayName"), teacher), true);
        teacher.remove("resumePdfUrl");
        teacher.remove("resumePdfName");
        teacher.remove("is_school_verified");
        teacher.remove("is_skill_verified");
        teacher.remove("avatar_url"); // Relationship snapshots retain teaching details. Avatars are read live.
        return teacher;
    }
    private Map<String,Object> teacherProjectionForMatch(UUID id) {
        try { return teacherProjection(id); }
        catch(ApiError e) { throw new ApiError(422,"PROFILE_NOT_PUBLIC","老师须先补齐必填资料并主动公开，才能投递或被邀请"); }
    }
    private Map<String,Object> teacherProjection(UUID id) {
        List<Map<String, Object>> rows = db.queryForList(TEACHER_QUERY +
            "a.id=? AND a.email_verified AND a.business_identity='TUTOR' AND p.is_public", id);
        if (rows.isEmpty()) throw new ApiError(404, "NOT_FOUND", "老师资料当前不可查看");
        return teacherProjection(rows.getFirst());
    }
    private void addVerificationFlags(Map<String,Object> result, Map<String,Object> row) {
        result.put("is_school_verified", row.get("is_school_verified"));
        result.put("is_skill_verified", row.get("is_skill_verified"));
    }
    private Map<String,Object> teacherProjection(Map<String,Object> r) {
        var result = new LinkedHashMap<String, Object>();
        addVerificationFlags(result, r);
        addResumeFields(result, r, false);
        result.put("avatar_url", r.get("avatar_url"));
        result.put("id", r.get("id")); result.put("profileVersion", r.get("version")); result.put("displayName", r.get("display_name"));
        result.put("categoryCodes", values(r.get("category_codes"))); result.put("serviceModes", values(r.get("service_modes"))); result.put("areaCodes", values(r.get("area_codes")));
        result.put("introduction", r.get("introduction")); result.put("priceMin", amount(r.get("price_min"))); result.put("priceMax", amount(r.get("price_max"))); result.put("availabilityText", r.get("availability_text"));
        return result;
    }
}
