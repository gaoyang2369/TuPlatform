package cn.linke;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

record DraftPatch(Long expectedVersion, ObjectNode fields) {}
record SavedDraft(UUID id, String status, long version, ObjectNode fields, OffsetDateTime lastSavedAt, boolean retentionReminder) {}

@Service
@Transactional
class RequestDraftService {
    private final JdbcTemplate db; private final AuthService auth; private final ObjectMapper json;
    private final Set<String> categories;
    RequestDraftService(JdbcTemplate db, AuthService auth, ObjectMapper json, @Value("${app.request-categories:}") String configured) {
        this.db=db; this.auth=auth; this.json=json;
        categories = Arrays.stream(configured.split(",")).map(String::strip).filter(s->!s.isEmpty()).collect(java.util.stream.Collectors.toUnmodifiableSet());
        if(!Rules.CATEGORIES.containsAll(categories)) throw new IllegalArgumentException("Unknown REQUEST_CATEGORIES");
    }
    Map<String,Object> options() { return Map.of("categories",categories.stream().sorted().toList(),"streets",Rules.AREAS.stream().sorted().toList(),"audienceStages",List.of("PRIMARY","JUNIOR","SENIOR","ADULT","OTHER"),"serviceModes",List.of("ONLINE","OFFLINE")); }
    private SavedDraft map(java.sql.ResultSet r,int index) throws java.sql.SQLException {
        try { return new SavedDraft(r.getObject("id",UUID.class),r.getString("status"),r.getLong("version"),(ObjectNode)json.readTree(r.getString("fields")),r.getObject("last_saved_at",OffsetDateTime.class),r.getBoolean("reminder")); }
        catch(java.io.IOException e) { throw new IllegalStateException("Stored draft invalid",e); }
    }
    private static final String READ="SELECT *, last_saved_at<=now()-interval '83 days' AS reminder FROM service_requests ";
    private SavedDraft own(UUID user,UUID id,boolean lock) {
        var rows=db.query(READ+"WHERE id=? AND owner_id=? AND (status<>'DRAFT' OR last_saved_at>now()-interval '90 days')"+(lock?" FOR UPDATE":""),this::map,id,user);
        if(rows.isEmpty()) throw new ApiError(404,"NOT_FOUND","需求不存在或不可查看");
        return rows.getFirst();
    }
    public SavedDraft get(String session,UUID id) { Account a=auth.business(session,"REQUESTER"); return own(a.id(),id,false); }
    public SavedDraft create(String session,String key) {
        Account a=auth.business(session,"REQUESTER");
        if(key==null || !key.matches("[A-Za-z0-9_-]{16,100}")) throw ApiError.invalid("idempotencyKey","请提供有效的重复提交凭据");
        var receipt=db.queryForList("SELECT request_id FROM request_creation_receipts WHERE owner_id=? AND idempotency_key=?",a.id(),key);
        if(!receipt.isEmpty()) {
            if(receipt.getFirst().get("request_id")==null) throw new ApiError(410,"DRAFT_REMOVED","原草稿已清理，请返回列表重新创建");
            return own(a.id(),(UUID)receipt.getFirst().get("request_id"),false);
        }
        UUID id=UUID.randomUUID();
        db.update("INSERT INTO service_requests(id,owner_id) VALUES (?,?)",id,a.id());
        db.update("INSERT INTO request_creation_receipts(owner_id,idempotency_key,request_id) VALUES (?,?,?)",a.id(),key,id);
        return own(a.id(),id,false);
    }
    public SavedDraft save(String session,UUID id,DraftPatch patch) {
        Account a=auth.business(session,"REQUESTER"); SavedDraft current=own(a.id(),id,true);
        if(!current.status().equals("DRAFT")) throw new ApiError(409,"REQUEST_READ_ONLY","当前需求不可修改，请读取最新状态");
        if(patch.expectedVersion()==null || patch.fields()==null) throw ApiError.invalid("expectedVersion","请读取当前草稿并提供要保存的字段");
        if(patch.expectedVersion()!=current.version()) throw new ApiError(409,"VERSION_CONFLICT","草稿已在其他页面修改，请读取最新内容后再保存；当前输入仍保留");
        DraftRules.checkNames(patch.fields());
        ObjectNode merged=current.fields().deepCopy();
        patch.fields().fields().forEachRemaining(e->{ if(e.getValue().isNull()) merged.remove(e.getKey()); else merged.set(e.getKey(),e.getValue()); });
        DraftRules.validate(merged,categories);
        try { db.update("UPDATE service_requests SET fields=CAST(? AS jsonb),version=version+1,last_saved_at=now() WHERE id=?",json.writeValueAsString(merged),id); }
        catch(com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("Draft encoding failed",e); }
        return own(a.id(),id,false);
    }
    public Map<String,Object> list(String session,String cursor) {
        Account a=auth.business(session,"REQUESTER"); OffsetDateTime time=OffsetDateTime.parse("9999-12-31T00:00:00Z"); UUID after=new UUID(-1,-1);
        if(cursor!=null && !cursor.isBlank()) {
            try { String[] parts=new String(Base64.getUrlDecoder().decode(cursor),java.nio.charset.StandardCharsets.UTF_8).split("\\|",-1); time=OffsetDateTime.parse(parts[0]); after=UUID.fromString(parts[1]); }
            catch(Exception e) { throw ApiError.invalid("cursor","列表位置已失效，请重新读取列表"); }
        }
        var rows=db.query(READ+"WHERE owner_id=? AND (status<>'DRAFT' OR last_saved_at>now()-interval '90 days') AND (last_saved_at,id)<(?,?) ORDER BY last_saved_at DESC,id DESC LIMIT 21",this::map,a.id(),time,after);
        List<SavedDraft> items=rows.stream().limit(20).toList();
        Map<String,Object> result=new LinkedHashMap<>(); result.put("items",items); result.put("nextCursor",null);
        if(rows.size()>20) { SavedDraft last=items.getLast(); result.put("nextCursor",Base64.getUrlEncoder().withoutPadding().encodeToString((last.lastSavedAt()+"|"+last.id()).getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        return result;
    }
    @Scheduled(fixedDelay=3600000,initialDelay=3600000)
    public void cleanupExpiredDrafts() { db.update("DELETE FROM service_requests WHERE status='DRAFT' AND last_saved_at<=now()-interval '90 days'"); }
}
