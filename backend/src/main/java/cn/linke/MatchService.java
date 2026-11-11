package cn.linke;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

record MatchStartResult(MatchView match,String outcome) {}

@Service
@Transactional
class MatchService {
    private final JdbcTemplate db; private final AuthService auth; private final ProfileService profiles;
    private final RequestPublicationService publication; private final ObjectMapper json; private final Secrets secrets; private final ContactService contacts;
    MatchService(JdbcTemplate db,AuthService auth,ProfileService profiles,RequestPublicationService publication,ObjectMapper json,Secrets secrets,ContactService contacts) {
        this.db=db;this.auth=auth;this.profiles=profiles;this.publication=publication;this.json=json;this.secrets=secrets;this.contacts=contacts;
    }
    private Account actor(String session) {
        Account a=auth.current(session,false);
        if (a.identity()==null) throw new ApiError(403,"IDENTITY_REQUIRED","请先确认业务身份");
        return a;
    }
    private String encode(Object value) { try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("Match encoding failed",e);} }
    private Map<String,Object> object(Object value) { try{return json.readValue(value.toString(),new TypeReference<Map<String,Object>>(){});}catch(Exception e){throw new IllegalStateException("Stored match invalid",e);} }
    // All match mutations lock BOTH participants in the same order before the request/relationship.
    // Unlike locking the actor first, this also permits simultaneous opposite intentions without deadlock.
    private void lockParticipants(String session,UUID requester,UUID tutor) {
        db.queryForList("SELECT a.id FROM accounts a JOIN profiles p ON p.account_id=a.id WHERE a.id IN (?,?) ORDER BY a.id FOR UPDATE OF a,p",requester,tutor);
        actor(session); // Recheck a revocable session after waiting for the locks.
    }
    private static final String READ="SELECT m.*,n.state,n.initiator_id,n.initiator_role,n.submission_id,n.teacher_profile_version,n.submission_snapshot,n.teacher_snapshot,"+
        "s.expires_at<=clock_timestamp() AS request_expired,p.is_public AS teacher_public,p.version AS current_teacher_version,r.latest_submission_id "+
        "FROM match_relationships m JOIN match_rounds n ON n.id=m.current_round_id LEFT JOIN request_submissions s ON s.id=n.submission_id "+
        "LEFT JOIN service_requests r ON r.id=m.request_id JOIN profiles p ON p.account_id=m.tutor_id ";
    private Map<String,Object> own(UUID actor,UUID id) {
        var rows=db.queryForList(READ+"WHERE m.id=? AND (m.requester_id=? OR m.tutor_id=?)",id,actor,actor);
        if(rows.isEmpty())throw new ApiError(404,"NOT_FOUND","撮合进度不存在或不可查看");
        return rows.getFirst();
    }
    private boolean blocked(UUID match,UUID submission,UUID actor) {
        return Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM match_rounds WHERE match_id=? AND submission_id IS NOT DISTINCT FROM ? AND initiator_id=? AND state='REJECTED')",Boolean.class,match,submission,actor));
    }
    private boolean canStart(Map<String,Object> r,UUID actor) {
        if(MatchRules.active((String)r.get("state"))||!Boolean.TRUE.equals(r.get("teacher_public")))return false;
        if(r.get("request_id")==null)return actor.equals(r.get("requester_id"))&&!blocked((UUID)r.get("id"),null,actor);
        try { var current=publication.publicDetail((UUID)r.get("request_id"));return !blocked((UUID)r.get("id"),(UUID)current.get("submissionId"),actor); }
        catch(ApiError e){return false;}
    }
    private MatchView view(Map<String,Object> r,UUID actor) {
        String state=(String)r.get("state"),role=actor.equals(r.get("requester_id"))?"REQUESTER":"TUTOR";
        boolean initiator=actor.equals(r.get("initiator_id")),waiting="WAITING".equals(state);
        return new MatchView((UUID)r.get("id"),(UUID)r.get("request_id"),((Number)r.get("version")).longValue(),(UUID)r.get("current_round_id"),state,
            (String)r.get("initiator_role"),role,waiting&&!initiator,Boolean.TRUE.equals(r.get("request_expired")),Boolean.TRUE.equals(r.get("teacher_public")),
            waiting&&!initiator,waiting&&!initiator,waiting&&initiator,canStart(r,actor),object(r.get("submission_snapshot")),object(r.get("teacher_snapshot")),
            !Objects.equals(r.get("submission_id"),r.get("latest_submission_id")),((Number)r.get("teacher_profile_version")).longValue()!=((Number)r.get("current_teacher_version")).longValue());
    }
    public MatchView detail(String session,UUID id) { Account a=actor(session);return view(own(a.id(),id),a.id()); }
    public Map<String,Object> list(String session,String cursor,String state,UUID requestId) {
        Account a=actor(session);UUID after=ProfileService.pageCursor(cursor);
        List<Object> args=new ArrayList<>(List.of(a.id(),a.id(),after));
        String filter="(m.requester_id=? OR m.tutor_id=?) AND m.id<?";
        if(state!=null&&!state.isBlank()) {if(!Set.of("WAITING","MUTUAL","REJECTED","WITHDRAWN").contains(state))throw ApiError.invalid("state","请选择有效进度");filter+=" AND n.state=?";args.add(state);}
        if(requestId!=null) {filter+=" AND m.request_id=?";args.add(requestId);}
        var rows=db.queryForList(READ+"WHERE "+filter+" ORDER BY m.id DESC LIMIT 21",args.toArray());
        var result=new LinkedHashMap<String,Object>();result.put("items",rows.stream().limit(20).map(r->view(r,a.id())).toList());
        result.put("nextCursor",rows.size()>20?rows.get(19).get("id").toString():null);return result;
    }
    private <T> T replay(UUID actor,String action,String key,Object body,Class<T> type) {
        if(key==null||!key.matches("[A-Za-z0-9_-]{16,100}"))throw ApiError.invalid("idempotencyKey","请提供有效的重复提交凭据");
        var rows=db.queryForList("SELECT fingerprint,result FROM match_action_receipts WHERE actor_id=? AND action=? AND idempotency_key=?",actor,action,key);
        if(rows.isEmpty())return null;
        if(!secrets.digest(encode(body)).equals(rows.getFirst().get("fingerprint")))throw new ApiError(409,"IDEMPOTENCY_CONFLICT","同一次操作的内容已变化，请重新核对");
        try{return json.readValue(rows.getFirst().get("result").toString(),type);}catch(Exception e){throw new IllegalStateException("Stored match receipt invalid",e);}
    }
    private <T> T remember(UUID actor,String action,String key,UUID match,Object body,T value) {
        db.update("INSERT INTO match_action_receipts(actor_id,action,idempotency_key,match_id,fingerprint,result) VALUES(?,?,?,?,?,CAST(? AS jsonb))",actor,action,key,match,secrets.digest(encode(body)),encode(value));return value;
    }
    public MatchStartResult start(String session,StartMatch body,String key) {
        Account a=actor(session);
        if("DIRECT_INVITATION".equals(body.kind()))return directInvitation(session,a,body,key);
        boolean application="APPLICATION".equals(body.kind());
        if(!application&&!"INVITATION".equals(body.kind()))throw ApiError.invalid("kind","请选择投递或邀请");
        if(!(application?"TUTOR":"REQUESTER").equals(a.identity()))throw new ApiError(403,"IDENTITY_NOT_ALLOWED","当前身份不能发起此类意向");
        if(body.requestId()==null||body.expectedSubmissionId()==null||body.expectedTeacherProfileVersion()==null)throw ApiError.invalid("requestId","请先读取并核对需求与老师资料");
        if(application&&body.tutorUserId()!=null)throw ApiError.invalid("tutorUserId","老师只能以本人身份投递");
        UUID tutor=application?a.id():body.tutorUserId();
        if(tutor==null)throw ApiError.invalid("tutorUserId","请选择老师");
        var requests=db.queryForList("SELECT owner_id FROM service_requests WHERE id=?"+(application?"":" AND owner_id=?"),application?new Object[]{body.requestId()}:new Object[]{body.requestId(),a.id()});
        if(requests.isEmpty())throw new ApiError(404,"NOT_FOUND","需求不存在或不可操作");
        UUID requester=(UUID)requests.getFirst().get("owner_id");
        lockParticipants(session,requester,tutor);
        db.queryForList("SELECT id FROM service_requests WHERE id=? FOR UPDATE",body.requestId());
        MatchStartResult replay=replay(a.id(),"START",key,body,MatchStartResult.class);if(replay!=null)return replay;
        var direct=db.queryForList("SELECT m.id FROM match_relationships m JOIN match_rounds n ON n.id=m.current_round_id WHERE m.request_id IS NULL AND m.requester_id=? AND m.tutor_id=? AND n.state IN ('WAITING','MUTUAL') FOR UPDATE OF m",requester,tutor);
        if(!direct.isEmpty()){
            UUID existing=(UUID)direct.getFirst().get("id");
            return remember(a.id(),"START",key,existing,body,new MatchStartResult(detail(session,existing),"EXISTING"));
        }
        var previous=db.queryForList("SELECT id FROM match_relationships WHERE request_id=? AND tutor_id=? FOR UPDATE",body.requestId(),tutor);
        UUID match=previous.isEmpty()?UUID.randomUUID():(UUID)previous.getFirst().get("id");
        if(!previous.isEmpty()&&MatchRules.active((String)own(a.id(),match).get("state")))
            return remember(a.id(),"START",key,match,body,new MatchStartResult(detail(session,match),"EXISTING"));
        var submission=db.queryForList("SELECT r.status,s.status AS submission_status,s.expires_at<=clock_timestamp() AS expired FROM service_requests r LEFT JOIN request_submissions s ON s.id=r.latest_submission_id WHERE r.id=?",body.requestId());
        // An unrelated tutor cannot use a start error to discover a private draft/submission.
        if(application&&previous.isEmpty()&&(submission.isEmpty()||!"OPEN".equals(submission.getFirst().get("status"))||!"APPROVED".equals(submission.getFirst().get("submission_status"))))
            throw new ApiError(404,"NOT_FOUND","需求不存在或当前不可投递");
        if(!submission.isEmpty()&&Boolean.TRUE.equals(submission.getFirst().get("expired")))throw new ApiError(422,"REQUEST_EXPIRED","需求已到期，不能新增或重新发起意向");
        Map<String,Object> snapshot;
        try{snapshot=publication.publicDetail(body.requestId());}catch(ApiError e){
            if(application&&previous.isEmpty())throw new ApiError(404,"NOT_FOUND","需求不存在或当前不可投递");
            throw new ApiError(422,"REQUEST_NOT_OPEN","需求当前未有效公开，不能新增或重新发起意向");
        }
        if(!body.expectedSubmissionId().equals(snapshot.get("submissionId")))throw new ApiError(409,"SUBMISSION_CHANGED","需求提交版本已变化，请重新读取并核对");
        Map<String,Object> teacher=profiles.matchTeacher(tutor,body.expectedTeacherProfileVersion());
        if(!previous.isEmpty()&&blocked(match,body.expectedSubmissionId(),a.id()))throw new ApiError(422,"REAPPLY_BLOCKED","本提交版本中你的意向已被拒绝，不能再次发起；可等待对方主动邀请或投递");
        UUID round=UUID.randomUUID();
        if(previous.isEmpty())db.update("INSERT INTO match_relationships(id,request_id,requester_id,tutor_id,current_round_id) VALUES(?,?,?,?,?)",match,body.requestId(),requester,tutor,round);
        db.update("INSERT INTO match_rounds(id,match_id,submission_id,initiator_id,initiator_role,state,teacher_profile_version,submission_snapshot,teacher_snapshot) VALUES(?,?,?,?,?,'WAITING',?,CAST(? AS jsonb),CAST(? AS jsonb))",round,match,body.expectedSubmissionId(),a.id(),a.identity(),body.expectedTeacherProfileVersion(),encode(snapshot),encode(teacher));
        if(!previous.isEmpty())db.update("UPDATE match_relationships SET current_round_id=?,version=version+1 WHERE id=?",round,match);
        contacts.exchangeWithIntention(a.id(),match,body.contactExchange());
        return remember(a.id(),"START",key,match,body,new MatchStartResult(detail(session,match),"CREATED"));
    }
    private MatchStartResult directInvitation(String session,Account a,StartMatch body,String key) {
        if(!"REQUESTER".equals(a.identity()))throw new ApiError(403,"IDENTITY_NOT_ALLOWED","仅需求方可以直接邀请老师");
        if(body.requestId()!=null||body.expectedSubmissionId()!=null)throw ApiError.invalid("requestId","直接邀请无需关联公开需求");
        if(body.tutorUserId()==null||body.expectedTeacherProfileVersion()==null)throw ApiError.invalid("tutorUserId","请先读取老师资料");
        String introduction=body.introduction()==null?"":body.introduction().strip();
        if(introduction.codePointCount(0,introduction.length())>500||introduction.codePoints().anyMatch(c->Character.isISOControl(c)&&c!='\n'&&c!='\r'&&c!='\t'))
            throw ApiError.invalid("introduction","邀请留言限500字，请勿包含控制字符");
        UUID tutor=body.tutorUserId();
        lockParticipants(session,a.id(),tutor);
        MatchStartResult replay=replay(a.id(),"START",key,body,MatchStartResult.class);if(replay!=null)return replay;
        var active=db.queryForList("SELECT m.id FROM match_relationships m JOIN match_rounds n ON n.id=m.current_round_id WHERE m.requester_id=? AND m.tutor_id=? AND n.state IN ('WAITING','MUTUAL') ORDER BY m.created_at,m.id LIMIT 1 FOR UPDATE OF m",a.id(),tutor);
        if(!active.isEmpty()){
            UUID existing=(UUID)active.getFirst().get("id");
            return remember(a.id(),"START",key,existing,body,new MatchStartResult(detail(session,existing),"EXISTING"));
        }
        var previous=db.queryForList("SELECT id FROM match_relationships WHERE request_id IS NULL AND requester_id=? AND tutor_id=? FOR UPDATE",a.id(),tutor);
        UUID match=previous.isEmpty()?UUID.randomUUID():(UUID)previous.getFirst().get("id");
        if(!previous.isEmpty()&&MatchRules.active((String)own(a.id(),match).get("state")))
            return remember(a.id(),"START",key,match,body,new MatchStartResult(detail(session,match),"EXISTING"));
        Map<String,Object> teacher=profiles.matchTeacher(tutor,body.expectedTeacherProfileVersion());
        if(!previous.isEmpty()&&blocked(match,null,a.id()))throw new ApiError(422,"REAPPLY_BLOCKED","老师已婉拒本次邀请，请尊重对方选择，不能重复邀请");
        String name=profiles.get(session).displayName();
        if(name==null||name.isBlank())throw new ApiError(422,"PROFILE_REQUIRED","请先填写本人展示名，让老师知道是谁发来的邀请");
        Map<String,Object> snapshot=Map.of("title","直接邀请","publisherDisplayName",name,"introduction",introduction);
        UUID round=UUID.randomUUID();
        if(previous.isEmpty())db.update("INSERT INTO match_relationships(id,requester_id,tutor_id,current_round_id) VALUES(?,?,?,?)",match,a.id(),tutor,round);
        db.update("INSERT INTO match_rounds(id,match_id,initiator_id,initiator_role,state,teacher_profile_version,submission_snapshot,teacher_snapshot) VALUES(?,?,?,'REQUESTER','WAITING',?,CAST(? AS jsonb),CAST(? AS jsonb))",round,match,a.id(),body.expectedTeacherProfileVersion(),encode(snapshot),encode(teacher));
        if(!previous.isEmpty())db.update("UPDATE match_relationships SET current_round_id=?,version=version+1 WHERE id=?",round,match);
        contacts.exchangeWithIntention(a.id(),match,body.contactExchange());
        return remember(a.id(),"START",key,match,body,new MatchStartResult(detail(session,match),"CREATED"));
    }
    public MatchView respond(String session,UUID id,RespondMatch body,String key,String action) {
        Account a=actor(session);var initial=own(a.id(),id);
        lockParticipants(session,(UUID)initial.get("requester_id"),(UUID)initial.get("tutor_id"));
        db.queryForList("SELECT id FROM match_relationships WHERE id=? FOR UPDATE",id);
        var fingerprint=Map.of("id",id,"body",body);
        MatchView replay=replay(a.id(),action,key,fingerprint,MatchView.class);if(replay!=null)return replay;
        var current=own(a.id(),id);
        if(body.roundId()==null||body.expectedVersion()==null)throw ApiError.invalid("expectedVersion","请读取当前轮次与进度");
        if(!body.roundId().equals(current.get("current_round_id"))||body.expectedVersion().longValue()!=((Number)current.get("version")).longValue())throw new ApiError(409,"VERSION_CONFLICT","意向已变化，请读取最新进度后核对");
        MatchRules.response((String)current.get("state"),a.id().equals(current.get("initiator_id")),action);
        String state=switch(action){case "ACCEPT"->"MUTUAL";case "REJECT"->"REJECTED";default->"WITHDRAWN";};
        db.update("UPDATE match_rounds SET state=?,responded_by=?,responded_at=clock_timestamp() WHERE id=?",state,a.id(),body.roundId());
        db.update("UPDATE match_relationships SET version=version+1 WHERE id=?",id);
        if("ACCEPT".equals(action))contacts.exchangeWithIntention(a.id(),id,body.contactExchange());
        return remember(a.id(),action,key,id,fingerprint,detail(session,id));
    }
}
