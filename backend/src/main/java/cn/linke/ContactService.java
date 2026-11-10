package cn.linke;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class ContactService {
    private final JdbcTemplate db; private final AuthService auth; private final Secrets secrets; private final ObjectMapper json;
    ContactService(JdbcTemplate db,AuthService auth,Secrets secrets,ObjectMapper json) { this.db=db;this.auth=auth;this.secrets=secrets;this.json=json; }
    private Account actor(String session) {
        Account a=auth.current(session,false);
        if(a.identity()==null)throw new ApiError(403,"IDENTITY_REQUIRED","请先确认业务身份");
        return a;
    }
    private Contacts stored(UUID actor) {
        var rows=db.query("SELECT * FROM private_contacts WHERE account_id=?",(r,i)->new Contacts(r.getLong("version"),r.getString("phone"),r.getString("wechat"),new ContactFields(r.getLong("phone_version"),r.getLong("wechat_version"))),actor);
        return rows.isEmpty()?new Contacts(0,null,null,new ContactFields(0L,0L)):rows.getFirst();
    }
    public Contacts get(String session) { return stored(actor(session).id()); }
    public Map<String,Object> save(String session,SaveContacts body) {
        // No participant locks are taken after this actor lock. A contact edit locks affected
        // relationships by ID only; grant operations lock BOTH accounts first, then relationships.
        Account a=auth.business(session,null);Contacts before=stored(a.id());
        if(body.expectedVersion()==null || body.expectedVersion()!=before.version())throw conflict();
        String phone=ContactRules.phone(body.phone()),wechat=ContactRules.wechat(body.wechat());
        boolean phoneChanged=!Objects.equals(phone,before.phone()),wechatChanged=!Objects.equals(wechat,before.wechat());
        if(!phoneChanged&&!wechatChanged)return Map.of("contacts",before,"affectedGrantCount",0);
        db.update("INSERT INTO private_contacts(account_id) VALUES(?) ON CONFLICT DO NOTHING",a.id());
        db.update("UPDATE private_contacts SET phone=?,wechat=?,version=version+1,phone_version=phone_version+?,wechat_version=wechat_version+? WHERE account_id=?",phone,wechat,phoneChanged?1:0,wechatChanged?1:0,a.id());
        // Selected fields remain recorded even while awaiting the other party or after invalidation.
        // Reconfirming one side before another edit must never authorize a newly edited value.
        var affected=db.queryForList("SELECT m.id,m.current_round_id FROM match_relationships m JOIN contact_grants g ON g.round_id=m.current_round_id WHERE "+
            "(m.requester_id=? AND ((? AND g.requester_phone_version IS NOT NULL) OR (? AND g.requester_wechat_version IS NOT NULL))) OR "+
            "(m.tutor_id=? AND ((? AND g.tutor_phone_version IS NOT NULL) OR (? AND g.tutor_wechat_version IS NOT NULL))) ORDER BY m.id FOR UPDATE OF m",
            a.id(),phoneChanged,wechatChanged,a.id(),phoneChanged,wechatChanged);
        for(var row:affected)db.update("UPDATE contact_grants SET requester_agreed=false,tutor_agreed=false,requester_reconfirm=true,tutor_reconfirm=true,version=version+1 WHERE round_id=?",row.get("current_round_id"));
        return Map.of("contacts",stored(a.id()),"affectedGrantCount",affected.size());
    }
    private ApiError conflict() { return new ApiError(409,"VERSION_CONFLICT","联系方式或授权进度已变化，请读取最新内容并核对"); }
    private Map<String,Object> own(UUID actor,UUID id) {
        var rows=db.queryForList("SELECT m.*,n.state FROM match_relationships m JOIN match_rounds n ON n.id=m.current_round_id WHERE m.id=? AND (m.requester_id=? OR m.tutor_id=?)",id,actor,actor);
        if(rows.isEmpty())throw new ApiError(404,"NOT_FOUND","撮合进度不存在或不可查看");
        return rows.getFirst();
    }
    private Map<String,Object> locked(String session,Account a,UUID id) {
        var initial=own(a.id(),id);
        // Same lock order as MatchService. Do not call auth.business(actor) before these locks.
        db.queryForList("SELECT a.id FROM accounts a JOIN profiles p ON p.account_id=a.id WHERE a.id IN (?,?) ORDER BY a.id FOR UPDATE OF a,p",initial.get("requester_id"),initial.get("tutor_id"));
        actor(session);
        db.queryForList("SELECT id FROM match_relationships WHERE id=? FOR UPDATE",id);
        return own(a.id(),id);
    }
    private Map<String,Object> state(Map<String,Object> match) {
        var rows=db.queryForList("SELECT * FROM contact_grants WHERE round_id=?",match.get("current_round_id"));
        return rows.isEmpty()?Map.of("version",0L,"requester_agreed",false,"tutor_agreed",false,"requester_reconfirm",false,"tutor_reconfirm",false):rows.getFirst();
    }
    private List<String> selected(Map<String,Object> g,String side) {
        List<String> result=new ArrayList<>();
        if(g.get(side+"_phone_version")!=null)result.add("PHONE");
        if(g.get(side+"_wechat_version")!=null)result.add("WECHAT");return result;
    }
    private boolean valid(Map<String,Object> g,String side,Contacts c) {
        if(!Boolean.TRUE.equals(g.get(side+"_agreed"))||Boolean.TRUE.equals(g.get(side+"_reconfirm")))return false;
        var fields=selected(g,side);if(fields.isEmpty())return false;
        return (!fields.contains("PHONE")||(c.phone()!=null&&Objects.equals(g.get(side+"_phone_version"),c.fieldVersions().phone()))) &&
            (!fields.contains("WECHAT")||(c.wechat()!=null&&Objects.equals(g.get(side+"_wechat_version"),c.fieldVersions().wechat())));
    }
    private Map<String,String> values(Map<String,Object> g,String side,Contacts c) {
        Map<String,String> result=new LinkedHashMap<>();var fields=selected(g,side);
        if(fields.contains("PHONE"))result.put("phone",c.phone());if(fields.contains("WECHAT"))result.put("wechat",c.wechat());return result;
    }
    private Map<String,Object> view(Map<String,Object> m,UUID actor) {
        var g=state(m);boolean requester=actor.equals(m.get("requester_id"));String mine=requester?"requester":"tutor",other=requester?"tutor":"requester";
        Contacts r=stored((UUID)m.get("requester_id")),t=stored((UUID)m.get("tutor_id"));
        boolean my=valid(g,mine,requester?r:t),their=valid(g,other,requester?t:r);
        boolean visible="MUTUAL".equals(m.get("state"))&&my&&their;
        var result=new LinkedHashMap<String,Object>();result.put("roundId",m.get("current_round_id"));result.put("version",g.get("version"));
        result.put("myConsent",my);result.put("otherConsent",their);result.put("mySelectedFields",selected(g,mine));
        result.put("requiresReconfirmation",Boolean.TRUE.equals(g.get(mine+"_reconfirm")));result.put("otherRequiresReconfirmation",Boolean.TRUE.equals(g.get(other+"_reconfirm")));
        result.put("canViewContacts",visible);
        if(visible){result.put("mySharedContacts",values(g,mine,requester?r:t));result.put("otherSharedContacts",values(g,other,requester?t:r));}
        return result;
    }
    public Map<String,Object> grant(String session,UUID id) { Account a=actor(session);return view(locked(session,a,id),a.id()); }
    private String fingerprint(Object body) { try{return secrets.digest(json.writeValueAsString(body));}catch(Exception e){throw new IllegalStateException("Contact action encoding failed",e);} }
    private boolean replay(UUID actor,String action,String key,UUID id,Object body) {
        if(key==null||!key.matches("[A-Za-z0-9_-]{16,100}"))throw ApiError.invalid("idempotencyKey","请提供有效的重复提交凭据");
        var rows=db.queryForList("SELECT fingerprint,match_id FROM contact_action_receipts WHERE actor_id=? AND action=? AND idempotency_key=?",actor,action,key);
        if(rows.isEmpty())return false;
        if(!id.equals(rows.getFirst().get("match_id"))||!fingerprint(body).equals(rows.getFirst().get("fingerprint")))throw new ApiError(409,"IDEMPOTENCY_CONFLICT","同一次操作的内容已变化，请重新核对");
        return true;
    }
    private void remember(UUID actor,String action,String key,Map<String,Object> m,Object body) {
        db.update("INSERT INTO contact_action_receipts(actor_id,action,idempotency_key,match_id,round_id,fingerprint) VALUES(?,?,?,?,?,?)",actor,action,key,m.get("id"),m.get("current_round_id"),fingerprint(body));
    }
    private void check(Map<String,Object> m,Map<String,Object> g,UUID round,Long version) {
        if(round==null||version==null)throw ApiError.invalid("expectedGrantVersion","请先读取并核对本轮授权进度");
        if(!round.equals(m.get("current_round_id"))||version.longValue()!=((Number)g.get("version")).longValue())throw conflict();
        if(!"MUTUAL".equals(m.get("state")))throw new ApiError(422,"NOT_MUTUAL","仅已互选双方可以管理本轮联系方式授权");
    }
    public Map<String,Object> agree(String session,UUID id,AgreeContacts body,String key) {
        Account a=actor(session);var m=locked(session,a,id);
        // A receipt stores only the fingerprint. Replays always use CURRENT authorization/values.
        if(replay(a.id(),"AGREE",key,id,body))return view(m,a.id());
        var g=state(m);check(m,g,body.roundId(),body.expectedGrantVersion());
        if(!Boolean.TRUE.equals(body.agree()))throw ApiError.invalid("agree","请明确同意本次所选联系方式分享");
        var selected=ContactRules.fields(body.selectedFields());Contacts c=stored(a.id());ContactFields expected=body.expectedContactFieldVersions();
        for(String f:selected){boolean phone=f.equals("PHONE");if((phone?c.phone():c.wechat())==null)throw new ApiError(422,"CONTACT_FIELD_EMPTY","请先维护本人所选联系方式，再预览并同意");
            if(expected==null||!Objects.equals(phone?expected.phone():expected.wechat(),phone?c.fieldVersions().phone():c.fieldVersions().wechat()))throw new ApiError(409,"CONTACT_FIELD_CHANGED","本人所选联系方式已变化，请重新读取实际值并核对");}
        db.update("INSERT INTO contact_grants(round_id,match_id) VALUES(?,?) ON CONFLICT DO NOTHING",body.roundId(),id);
        String side=a.id().equals(m.get("requester_id"))?"requester":"tutor";
        db.update("UPDATE contact_grants SET "+side+"_agreed=true,"+side+"_reconfirm=false,"+side+"_phone_version=?,"+side+"_wechat_version=?,version=version+1 WHERE round_id=?",selected.contains("PHONE")?c.fieldVersions().phone():null,selected.contains("WECHAT")?c.fieldVersions().wechat():null,body.roundId());
        remember(a.id(),"AGREE",key,m,body);return view(m,a.id());
    }
    public Map<String,Object> withdraw(String session,UUID id,WithdrawContacts body,String key) {
        Account a=actor(session);var m=locked(session,a,id);
        if(replay(a.id(),"WITHDRAW",key,id,body))return view(m,a.id());
        var g=state(m);check(m,g,body.roundId(),body.expectedGrantVersion());
        db.update("INSERT INTO contact_grants(round_id,match_id) VALUES(?,?) ON CONFLICT DO NOTHING",body.roundId(),id);
        String side=a.id().equals(m.get("requester_id"))?"requester":"tutor";
        db.update("UPDATE contact_grants SET "+side+"_agreed=false,"+side+"_reconfirm=false,"+side+"_phone_version=NULL,"+side+"_wechat_version=NULL,version=version+1 WHERE round_id=?",body.roundId());
        remember(a.id(),"WITHDRAW",key,m,body);return view(m,a.id());
    }
}
