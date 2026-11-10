package cn.linke;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class RequestPublicationService {
    private final JdbcTemplate db;
    private final AuthService auth;
    private final RequestDraftService drafts;
    private final ObjectMapper json;
    private final Secrets secrets;
    RequestPublicationService(JdbcTemplate db, AuthService auth, RequestDraftService drafts, ObjectMapper json, Secrets secrets) {
        this.db=db; this.auth=auth; this.drafts=drafts; this.json=json; this.secrets=secrets;
    }
    private OffsetDateTime now() { return db.queryForObject("SELECT clock_timestamp()",OffsetDateTime.class); }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Request encoding failed",e); }
    }
    private ObjectNode fields(String value) {
        try { return (ObjectNode)json.readTree(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Stored submission invalid",e); }
    }
    private String fingerprint(UUID target,Object body) { return secrets.digest(target+":"+encode(body)); }
    private RequestAction receipt(UUID actor,String action,String key,UUID target,Object body) {
        if (key==null || !key.matches("[A-Za-z0-9_-]{16,100}")) throw ApiError.invalid("idempotencyKey","请提供有效的重复提交凭据");
        var rows=db.queryForList("SELECT fingerprint,result::text FROM request_action_receipts WHERE actor_id=? AND action=? AND idempotency_key=?",actor,action,key);
        if (rows.isEmpty()) return null;
        if (!rows.getFirst().get("fingerprint").equals(fingerprint(target,body))) throw new ApiError(409,"IDEMPOTENCY_CONFLICT","同一次操作的内容已变化，请重新核对");
        try { return json.readValue((String)rows.getFirst().get("result"),RequestAction.class); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Stored receipt invalid",e); }
    }
    private RequestAction remember(UUID actor,String action,String key,UUID target,Object body,RequestAction result) {
        db.update("INSERT INTO request_action_receipts(actor_id,action,idempotency_key,request_id,fingerprint,result) VALUES (?,?,?,?,?,CAST(? AS jsonb))",
            actor,action,key,result.id(),fingerprint(target,body),encode(result));
        return result;
    }
    private void version(Long expected,long actual) {
        if (expected==null) throw ApiError.invalid("expectedVersion","请先读取最新保存内容");
        if (expected!=actual) throw new ApiError(409,"VERSION_CONFLICT","内容已变化，请读取最新内容后再操作");
    }
    public RequestAction submit(String session,UUID id,SubmitRequest body,String key) {
        Account actor=auth.business(session,"REQUESTER");
        SavedDraft draft=drafts.own(actor.id(),id,true);
        RequestAction replay=receipt(actor.id(),"SUBMIT",key,id,body);
        if (replay!=null) return replay;
        version(body.expectedVersion(),draft.version());
        if (!draft.status().equals("DRAFT")) throw new ApiError(409,"REQUEST_READ_ONLY","当前需求不可提交，请读取最新状态");
        String name=db.queryForObject("SELECT display_name FROM profiles WHERE account_id=?",String.class,actor.id());
        OffsetDateTime submittedAt=now();
        SubmissionRules.validate(draft.fields(),drafts.categories(),name,body,submittedAt);
        UUID submission=UUID.randomUUID();
        db.update("INSERT INTO request_submissions(id,request_id,fields,publisher_display_name,publisher_declaration,declaration_version,submitted_at,expires_at) VALUES (?,?,CAST(? AS jsonb),?,?,?,?,?)",
            submission,id,encode(draft.fields()),name,body.publisherDeclaration(),body.declarationVersion(),submittedAt,SubmissionRules.expiresAt(submittedAt));
        db.update("UPDATE service_requests SET status='PENDING_REVIEW',version=version+1,latest_submission_id=? WHERE id=?",submission,id);
        return remember(actor.id(),"SUBMIT",key,id,body,new RequestAction(id,submission,"PENDING_REVIEW",draft.version()+1));
    }
    public RequestAction revise(String session,UUID id,VersionBody body,String key) {
        Account actor=auth.business(session,"REQUESTER");
        SavedDraft current=drafts.own(actor.id(),id,true);
        RequestAction replay=receipt(actor.id(),"REVISE",key,id,body);
        if (replay!=null) return replay;
        version(body.expectedVersion(),current.version());
        if (!Set.of("REJECTED","EXPIRED").contains(current.status()) || current.latestSubmission()==null)
            throw new ApiError(409,"REVISION_NOT_ALLOWED","当前需求不能修订，请读取最新状态");
        db.update("UPDATE service_requests SET status='DRAFT',fields=CAST(? AS jsonb),version=version+1,last_saved_at=clock_timestamp() WHERE id=?",encode(current.latestSubmission().fields()),id);
        return remember(actor.id(),"REVISE",key,id,body,new RequestAction(id,current.latestSubmission().id(),"DRAFT",current.version()+1));
    }
    private Account operator(String session) {
        Account actor=auth.current(session,true);
        if (!actor.operator()) throw new ApiError(403,"OPERATOR_REQUIRED","当前账号没有需求审核权限");
        return actor;
    }
    private static final String REVIEW_READ="SELECT s.*,r.owner_id,d.reason AS review_feedback,"+
        "CASE WHEN s.status<>'REJECTED' AND s.expires_at<=clock_timestamp() THEN 'EXPIRED' ELSE s.status END AS effective_status,"+
        "(SELECT previous.reason FROM review_decisions previous JOIN request_submissions old ON old.id=previous.submission_id WHERE old.request_id=s.request_id AND old.id<>s.id AND old.submitted_at<s.submitted_at ORDER BY old.submitted_at DESC LIMIT 1) AS prior_review_feedback "+
        "FROM request_submissions s JOIN service_requests r ON r.id=s.request_id LEFT JOIN review_decisions d ON d.submission_id=s.id ";
    private ReviewSubmission reviewMap(java.sql.ResultSet r,int row) throws java.sql.SQLException {
        return new ReviewSubmission(r.getObject("id",UUID.class),r.getObject("request_id",UUID.class),r.getString("effective_status"),r.getLong("version"),r.getObject("submitted_at",OffsetDateTime.class),r.getObject("expires_at",OffsetDateTime.class),fields(r.getString("fields")),r.getString("publisher_display_name"),r.getString("publisher_declaration"),r.getString("declaration_version"),r.getString("review_feedback"),r.getString("prior_review_feedback"));
    }
    private ReviewSubmission review(UUID actor,UUID id) {
        var rows=db.query(REVIEW_READ+"WHERE s.id=? AND r.owner_id<>?",this::reviewMap,id,actor);
        if (rows.isEmpty()) throw new ApiError(404,"NOT_FOUND","提交不存在或不可审核");
        return rows.getFirst();
    }
    public ReviewSubmission reviewDetail(String session,UUID id) { return review(operator(session).id(),id); }
    private record Cursor(OffsetDateTime time, UUID id) {}
    private Cursor cursor(String value) {
        if (value==null || value.isBlank()) return new Cursor(OffsetDateTime.parse("9999-12-31T00:00:00Z"),new UUID(-1,-1));
        try {
            String[] parts=new String(Base64.getUrlDecoder().decode(value),StandardCharsets.UTF_8).split("\\|",-1);
            if (parts.length!=2) throw new IllegalArgumentException();
            return new Cursor(OffsetDateTime.parse(parts[0]),UUID.fromString(parts[1]));
        } catch (RuntimeException e) { throw ApiError.invalid("cursor","列表位置已失效，请重新读取"); }
    }
    private <T> Map<String,Object> page(List<T> rows,java.util.function.Function<T,Cursor> position) {
        List<T> items=rows.stream().limit(20).toList();
        Map<String,Object> result=new LinkedHashMap<>();result.put("items",items);result.put("nextCursor",null);
        if (rows.size()>20) {
            Cursor last=position.apply(items.getLast());
            result.put("nextCursor",Base64.getUrlEncoder().withoutPadding().encodeToString((last.time()+"|"+last.id()).getBytes(StandardCharsets.UTF_8)));
        }
        return result;
    }
    public Map<String,Object> reviewList(String session,String status,String value) {
        Account actor=operator(session); Cursor after=cursor(value);
        String state=status==null?"PENDING_REVIEW":status;
        if (!Set.of("PENDING_REVIEW","APPROVED","REJECTED","EXPIRED").contains(state)) throw ApiError.invalid("status","请选择有效的审核状态");
        String condition=state.equals("EXPIRED")?"s.status<>'REJECTED' AND s.expires_at<=clock_timestamp()":"s.status=?"+(state.equals("REJECTED")?"":" AND s.expires_at>clock_timestamp()");
        List<Object> args=new ArrayList<>(List.of(actor.id(),after.time(),after.id()));
        if (!state.equals("EXPIRED")) args.add(state);
        var rows=db.query(REVIEW_READ+"WHERE r.owner_id<>? AND r.latest_submission_id=s.id AND (s.submitted_at,s.id)<(?,?) AND "+condition+" ORDER BY s.submitted_at DESC,s.id DESC LIMIT 21",this::reviewMap,args.toArray());
        return page(rows,s->new Cursor(s.submittedAt(),s.id()));
    }
    public RequestAction decide(String session,UUID id,ReviewRequest body,String key) {
        Account actor=operator(session);
        ReviewSubmission initial=review(actor.id(),id);
        // Lock the request before reading the final state: two operators cannot both decide.
        var rows=db.queryForList("SELECT latest_submission_id,version FROM service_requests WHERE id=? FOR UPDATE",initial.requestId());
        ReviewSubmission submission=review(actor.id(),id);
        RequestAction replay=receipt(actor.id(),"REVIEW",key,id,body);
        if (replay!=null) return replay;
        version(body.expectedVersion(),submission.version());
        if (!id.equals(rows.getFirst().get("latest_submission_id")) || !submission.status().equals("PENDING_REVIEW"))
            throw new ApiError(409,"REVIEW_STATE_CHANGED","提交已处理或已到期，请读取最新状态");
        if (!Set.of("APPROVE","REJECT").contains(body.decision()==null?"":body.decision())) throw ApiError.invalid("decision","请选择通过或驳回");
        String reason=null;
        if (body.decision().equals("REJECT")) reason=Rules.text("reason",body.reason(),1000,true);
        else {
            if (body.reason()!=null && !body.reason().isBlank()) throw ApiError.invalid("reason","通过审核不填写驳回理由");
            DraftRules.validate(submission.fields(),drafts.categories());
        }
        OffsetDateTime reviewedAt=now();
        if (!submission.expiresAt().isAfter(reviewedAt)) throw new ApiError(409,"REVIEW_STATE_CHANGED","提交已到期，不能继续审核");
        db.update("INSERT INTO review_decisions(submission_id,reviewer_id,decision,reason,reviewed_at) VALUES (?,?,?,?,?)",id,actor.id(),body.decision(),reason,reviewedAt);
        db.update("UPDATE request_submissions SET status=?,version=version+1 WHERE id=?",body.decision().equals("APPROVE")?"APPROVED":"REJECTED",id);
        String status=body.decision().equals("APPROVE")?"OPEN":"REJECTED";
        db.update("UPDATE service_requests SET status=?,version=version+1 WHERE id=?",status,initial.requestId());
        return remember(actor.id(),"REVIEW",key,id,body,new RequestAction(initial.requestId(),id,status,((Number)rows.getFirst().get("version")).longValue()+1));
    }
    private static final List<String> PUBLIC_FIELDS=List.of("title","categoryCode","audienceStageCode","areaCode","goal","serviceMode","budgetMin","budgetMax","durationHours","frequency","availableSlots","availabilityNote","expectedStartDate","tutorExpectations");
    private record PublicRow(Map<String,Object> value,OffsetDateTime time,UUID id) {}
    private PublicRow publicMap(java.sql.ResultSet r,int row) throws java.sql.SQLException {
        ObjectNode saved=fields(r.getString("fields"));
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("id",r.getObject("request_id",UUID.class));result.put("submissionId",r.getObject("id",UUID.class));result.put("publisherDisplayName",r.getString("publisher_display_name"));
        for (String name:PUBLIC_FIELDS) if (saved.hasNonNull(name)) result.put(name,json.convertValue(saved.get(name),Object.class));
        return new PublicRow(result,r.getObject("submitted_at",OffsetDateTime.class),r.getObject("id",UUID.class));
    }
    private String publicCondition(List<Object> args) {
        if (drafts.categories().isEmpty()) return "FALSE";
        args.addAll(drafts.categories());
        return "r.status='OPEN' AND r.latest_submission_id=s.id AND s.status='APPROVED' AND s.expires_at>clock_timestamp() AND s.fields->>'categoryCode' IN ("+String.join(",",Collections.nCopies(drafts.categories().size(),"?"))+")";
    }
    public Map<String,Object> publicDetail(UUID request) {
        List<Object> args=new ArrayList<>(List.of(request));
        String condition=publicCondition(args);
        var rows=db.query("SELECT s.* FROM request_submissions s JOIN service_requests r ON r.id=s.request_id WHERE r.id=? AND "+condition,this::publicMap,args.toArray());
        if (rows.isEmpty()) throw new ApiError(404,"NOT_FOUND","需求不存在或当前不可查看");
        return rows.getFirst().value();
    }
    public Map<String,Object> publicList(String value,String category,String area,String mode) {
        Cursor after=cursor(value); List<Object> args=new ArrayList<>(List.of(after.time(),after.id()));
        String condition=publicCondition(args);
        String[][] filters={{"categoryCode",category},{"areaCode",area},{"serviceMode",mode}};
        for (String[] filter:filters) if (filter[1]!=null && !filter[1].isBlank()) {
            Set<String> allowed=switch(filter[0]) {case "categoryCode"->Rules.CATEGORIES;case "areaCode"->Rules.AREAS;default->Rules.MODES;};
            if (!allowed.contains(filter[1])) throw ApiError.invalid(filter[0],"请选择有效的筛选条件");
            condition+=" AND s.fields->>'"+filter[0]+"'=?";args.add(filter[1]);
        }
        var rows=db.query("SELECT s.* FROM request_submissions s JOIN service_requests r ON r.id=s.request_id WHERE (s.submitted_at,s.id)<(?,?) AND "+condition+" ORDER BY s.submitted_at DESC,s.id DESC LIMIT 21",this::publicMap,args.toArray());
        Map<String,Object> result=page(rows,r->new Cursor(r.time(),r.id()));
        result.put("items",rows.stream().limit(20).map(PublicRow::value).toList());
        return result;
    }
}
