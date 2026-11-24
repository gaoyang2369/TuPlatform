package cn.linke;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
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
    private final RequestDraftService drafts;
    ProfileService(JdbcTemplate db, AuthService auth, Secrets secrets, ObjectMapper json,
        @Value("${app.teacher-categories}") String categories, @Value("${app.pilot-school}") String pilotSchool, RequestDraftService drafts) {
        this.drafts = drafts;
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
    private static final Set<String> TRIAL_OFFERS = Set.of("支持线上免费沟通15分钟", "支持首次试课半价", "按正常时薪试课");
    static void validateResume(TeacherBody body) {
        Rules.text("schoolName", body.schoolName(), 100, false);
        Rules.text("collegeMajor", body.collegeMajor(), 100, false);
        Rules.text("gradeLevel", body.gradeLevel(), 30, false);
        Rules.text("yearsExp", body.yearsExp(), 80, false);
        requiredLabels("teachGrades", body.teachGrades());
        requiredLabels("teachSubjects", body.teachSubjects());
        if (Rules.choices("teachingStyleTags", body.teachingStyleTags(), STYLES, false).size() > 3)
            throw ApiError.invalid("teachingStyleTags", "最多选择三个教学风格标签");
        if (body.trialPolicy() == null || !("暂不支持试课".equals(body.trialPolicy()) || TRIAL_OFFERS.contains(body.trialPolicy())))
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
    public Map<String,Object> publicTeachers(String session, String cursor, String category, String area, String mode,
            String grade, String subject, boolean trialOnly, boolean verifiedOnly) {
        auth.business(session, "REQUESTER");
        var query = new StringBuilder(TEACHER_QUERY).append("a.email_verified AND a.business_identity='TUTOR' AND p.is_public");
        var args = new ArrayList<Object>();
        addTeacherFilter(query, args, "category_codes", category, Rules.CATEGORIES);
        addTeacherFilter(query, args, "area_codes", area, Rules.AREAS);
        addTeacherFilter(query, args, "service_modes", mode, Rules.MODES);
        addTeacherFilter(query, args, "teach_grades", Rules.text("grade", grade, 40, false).strip(), null);
        addTeacherFilter(query, args, "teach_subjects", Rules.text("subject", subject, 40, false).strip(), null);
        if (trialOnly) {
            query.append(" AND p.trial_policy = ANY (?)");
            args.add(TRIAL_OFFERS.toArray(String[]::new));
        }

        String priority = "(is_school_verified AND NULLIF(avatar_url,'') IS NOT NULL)";
        String sql = "SELECT visible.*, " + priority + " AS featured FROM (" + query + ") visible WHERE " +
                (verifiedOnly ? "is_school_verified" : "TRUE");
        // A plain UUID continues the ordinary tier, including URLs from before featured profiles were introduced.
        String prefix = "featured:";
        if (cursor != null && !cursor.isBlank()) {
            boolean featured = cursor.startsWith(prefix);
            String id = featured ? cursor.substring(prefix.length()) : cursor;
            if (id.isBlank()) throw ApiError.invalid("cursor", "列表位置已失效，请重新读取");
            sql += " AND (" + priority + ", id) < (?, ?)";
            args.add(featured);
            args.add(pageCursor(id));
        }
        var rows = db.queryForList(sql + " ORDER BY featured DESC, id DESC LIMIT 11", args.toArray());
        var result = new LinkedHashMap<String,Object>();
        result.put("items", rows.stream().limit(10).map(this::teacherProjection).toList());
        String nextCursor = null;
        if (rows.size() > 10) {
            var last = rows.get(9);
            nextCursor = (Boolean.TRUE.equals(last.get("featured")) ? prefix : "") + last.get("id");
        }
        result.put("nextCursor", nextCursor);
        return result;
    }

    public List<Map<String,Object>> recommendedTeachers(String session, UUID requestId) {
        var demand = drafts.get(session, requestId);
        if (!"PENDING_REVIEW".equals(demand.status()) || demand.latestSubmission() == null)
            throw new ApiError(409, "REQUEST_NOT_PENDING", "需求状态已变化，请刷新进度");
        var fields = demand.latestSubmission().fields();
        String street = RequestLocation.street(fields);
        String area = RequestLocation.STREETS.entrySet().stream().filter(e -> e.getValue().equals(street))
            .map(Map.Entry::getKey).findFirst().orElse("");
        String candidates = TEACHER_QUERY + "a.email_verified AND a.business_identity='TUTOR' AND p.is_public " +
            "AND p.category_codes @> CAST(? AS jsonb)";
        String sql = """
            SELECT id, display_name, avatar_url, college_major, grade_level, price_min, price_max, is_school_verified, reasons
            FROM (
                SELECT candidates.*, array_remove(ARRAY[
                    CASE WHEN jsonb_exists(area_codes, ?) THEN '同街道' END,
                    CASE WHEN jsonb_exists(teach_grades, ?) THEN '年级匹配' END,
                    CASE WHEN jsonb_exists(teach_subjects, ?) THEN '科目匹配' END,
                    CASE WHEN jsonb_exists(service_modes, ?) THEN '方式匹配' END,
                    CASE WHEN price_min <= ? AND price_max >= ? THEN '预算相符' END
                ], NULL) AS reasons
                FROM (""" + candidates + """
                ) candidates WHERE is_school_verified OR school_name=? OR (school_name IS NULL AND teach_grades <> '[]'::jsonb)
            ) scored WHERE cardinality(reasons) > 0
            ORDER BY cardinality(reasons) DESC, is_school_verified DESC, id DESC LIMIT 3
            """;
        return db.query(sql, (r, i) -> {
            Map<String,Object> card = new LinkedHashMap<>();
            card.put("id", r.getObject("id")); card.put("displayName", r.getString("display_name"));
            card.put("avatar_url", r.getString("avatar_url")); card.put("is_school_verified", r.getBoolean("is_school_verified"));
            card.put("collegeMajor", r.getString("college_major")); card.put("gradeLevel", r.getString("grade_level"));
            card.put("priceMin", amount(r.getBigDecimal("price_min"))); card.put("priceMax", amount(r.getBigDecimal("price_max")));
            var reasons = (String[])r.getArray("reasons").getArray();
            card.put("reasons", List.of(reasons));
            return card;
        }, area, fields.path("grade").asText(""), fields.path("subject").asText(""), fields.path("serviceMode").asText(""),
            new BigDecimal(fields.path("budgetMax").asText()), new BigDecimal(fields.path("budgetMin").asText()),
            encode(List.of(fields.path("categoryCode").asText())), pilotSchool);
    }

    private void addTeacherFilter(StringBuilder query, List<Object> args, String column, String value, Set<String> allowed) {
        if (value == null || value.isBlank()) return;
        if (allowed != null && !allowed.contains(value)) throw ApiError.invalid("filter", "请选择有效的筛选条件");
        query.append(" AND p.").append(column).append(" @> CAST(? AS jsonb)");
        args.add(encode(List.of(value)));
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
