package cn.linke;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Explicit, transactional synthetic fixtures; never a Flyway migration or startup hook. */
public class DemoData {
    static final String BATCH = "linke-demo-20261111-v1";
    static final String DOMAIN = "@seed.linke.invalid";
    static final ObjectMapper JSON = new ObjectMapper();
    static final Secrets SECRETS = new Secrets();
    static UUID id(String key) { return UUID.nameUUIDFromBytes((BATCH + ":" + key).getBytes(StandardCharsets.UTF_8)); }
    static String cfg(Properties p, String key, String fallback) { return System.getenv().getOrDefault(key,p.getProperty(key,fallback)); }
    static void require(boolean condition,String message) { if (!condition) throw new IllegalStateException(message); }
    static int count(Connection db,String sql,Object... args) throws Exception {
        try(var s=db.prepareStatement(sql)){ for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);try(var r=s.executeQuery()){r.next();return r.getInt(1);} }
    }
    static void update(Connection db,String sql,Object... args) throws Exception {
        try(var s=db.prepareStatement(sql)){for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);s.executeUpdate();}
    }
    static List<JsonNode> rows(JsonNode data,String key) { var out=new ArrayList<JsonNode>();data.path(key).forEach(out::add);return out; }
    static String account(JsonNode a) { return a.path("account").asText(); }
    static String text(JsonNode a,String key) { return a.path(key).asText(); }
    static String encode(JsonNode a) { return a.toString(); }
    static List<String> strings(JsonNode a) { var out=new ArrayList<String>();a.forEach(v->out.add(v.asText()));return out; }
    static void validate(JsonNode data,OffsetDateTime now) {
        require(BATCH.equals(text(data,"batch")),"Unexpected batch");
        var accounts=new HashSet<String>();var requests=new HashSet<String>();var titles=new HashSet<String>();
        for(var a:rows(data,"accounts")) {
            require(accounts.add(account(a)),"Duplicate account");Rules.account(account(a));
            require(account(a).matches("lk_demo_[rt][0-9]{3}"),"Unexpected seed account name");
            Rules.text("displayName",text(a,"displayName"),30,true);
            require(Set.of("REQUESTER","TUTOR").contains(text(a,"identity")),"Invalid identity");
            if(text(a,"identity").equals("TUTOR")) {
                var p=a.path("teacher");var modes=strings(p.path("serviceModes"));
                Rules.choices("categoryCodes",strings(p.path("categoryCodes")),Rules.CATEGORIES,true);
                Rules.choices("serviceModes",modes,Rules.MODES,true);
                Rules.choices("areaCodes",strings(p.path("areaCodes")),Rules.AREAS,modes.contains("OFFLINE"));
                Rules.text("introduction",text(p,"introduction"),1000,true);
                Rules.text("availabilityText",text(p,"availabilityText"),300,false);
                Rules.priceRange(Rules.money("priceMin",p.path("priceMin").isNull()?null:text(p,"priceMin")),Rules.money("priceMax",p.path("priceMax").isNull()?null:text(p,"priceMax")));
                naturalText(text(p,"introduction"));
            }
        }
        for(var r:rows(data,"requests")) {
            require(requests.add(text(r,"key")),"Duplicate request key");require(accounts.contains(text(r,"owner")),"Missing owner");
            var fields=(ObjectNode)r.path("fields");require(titles.add(text(fields,"title")),"Duplicate title");
            naturalText(text(fields,"title"));naturalText(text(fields,"goal"));naturalText(text(r,"publisherDisplayName"));
            require(text(fields,"locationText").startsWith("深圳市坪山区") && text(fields,"locationText").length()>8,"Neighborhood-level location required");
            if(text(r,"status").equals("DRAFT")) DraftRules.validate(fields,Rules.CATEGORIES);
            else SubmissionRules.validate(fields,Rules.CATEGORIES,text(r,"publisherDisplayName"),new SubmitRequest(1L,text(r,"declaration"),"publisher-v1"),text(r,"status").equals("EXPIRED")?now.minusDays(18):now);
        }
    }
    static void naturalText(String value) {
        require(List.of("合成测试","测试样本","·样本","流程样本","【测试","模拟设定","流程说明","虚构","测试区间").stream().noneMatch(value::contains),"Fixture marker found in user-facing content");
    }
    static void summary(Connection db,JsonNode data) throws Exception {
        var report=new LinkedHashMap<String,Object>();
        report.put("database",db.getCatalog());report.put("batch",BATCH);
        report.put("accountsTotal",count(db,"SELECT count(*) FROM accounts"));
        report.put("seedAccounts",count(db,"SELECT count(*) FROM accounts WHERE right(email,?)=?",DOMAIN.length(),DOMAIN));
        report.put("requestsTotal",count(db,"SELECT count(*) FROM service_requests"));
        report.put("publicTeachersTotal",count(db,"SELECT count(*) FROM profiles WHERE is_public"));
        var stats=new ArrayList<Map<String,Object>>();
        try(var s=db.prepareStatement("SELECT r.status,r.fields->>'categoryCode' category,count(*) n FROM service_requests r JOIN accounts a ON a.id=r.owner_id WHERE right(a.email,?)=? GROUP BY 1,2 ORDER BY 1,2")) {
            s.setInt(1,DOMAIN.length());s.setString(2,DOMAIN);try(var r=s.executeQuery()){while(r.next())stats.add(Map.of("status",r.getString(1),"category",r.getString(2),"count",r.getInt(3)));}
        }
        report.put("requestsByStatusAndCategory",stats);System.out.println(JSON.writeValueAsString(report));
    }
    static void verify(Connection db,JsonNode data) throws Exception {
        for(var a:rows(data,"accounts")) {
            require(count(db,"SELECT count(*) FROM accounts a JOIN profiles p ON p.account_id=a.id WHERE a.id=? AND a.account_key=? AND a.email=? AND a.business_identity=? AND a.email_verified AND NOT a.operator_granted",id(account(a)),account(a),account(a)+DOMAIN,text(a,"identity"))==1,"Seed account missing or changed");
            if(text(a,"identity").equals("TUTOR")) require(count(db,"SELECT count(*) FROM profiles WHERE account_id=? AND is_public AND introduction=?",id(account(a)),text(a.path("teacher"),"introduction"))==1,"Seed teacher missing or changed");
        }
        for(var r:rows(data,"requests")) {
            require(count(db,"SELECT count(*) FROM service_requests WHERE id=? AND owner_id=? AND status=? AND fields=CAST(? AS jsonb)",id(text(r,"key")),id(text(r,"owner")),text(r,"status"),encode(r.path("fields")))==1,"Seed request missing or changed");
            if(!text(r,"status").equals("DRAFT")) require(count(db,"SELECT count(*) FROM service_requests r JOIN request_submissions s ON s.id=r.latest_submission_id WHERE r.id=? AND s.request_id=r.id AND s.fields=r.fields AND s.publisher_display_name=? AND s.publisher_declaration=? AND s.declaration_version='publisher-v1'",id(text(r,"key")),text(r,"publisherDisplayName"),text(r,"declaration"))==1,"Seed submission mismatch");
        }
        require(count(db,"SELECT count(*) FROM private_contacts p JOIN accounts a ON a.id=p.account_id WHERE right(a.email,?)=?",DOMAIN.length(),DOMAIN)==0,"Unexpected synthetic private contacts");
        System.out.println("VERIFY_OK accounts="+data.path("accounts").size()+" requests="+data.path("requests").size());
    }
    public static void main(String[] args) throws Exception {
        try { run(args); }
        catch(ApiError e) {System.err.println("VALIDATION_FAILED "+e.fields);System.exit(1);}
        catch(Exception e) {System.err.println("DEMO_DATA_FAILED "+e.getClass().getSimpleName()+" "+(e instanceof IllegalStateException?e.getMessage():"Check local configuration and database availability; secrets omitted."));System.exit(1);}
    }
    static void run(String[] args) throws Exception {
        require(args.length==5,"Expected action, env, catalog, output, database");
        String action=args[0];require(Set.of("inspect","dry-run","apply","verify","remove","refresh-content").contains(action),"Invalid action");
        require(Set.of("tuplatform","tuplatform_test").contains(args[4]),"Unexpected target database");
        JsonNode data=JSON.readTree(Files.readString(Path.of(args[2])));
        Properties config=new Properties();try(var r=Files.newBufferedReader(Path.of(args[1]))){config.load(r);}
        String url=cfg(config,"DB_URL",null);
        if(url==null)url="jdbc:postgresql://"+cfg(config,"DB_HOST","localhost")+":"+cfg(config,"DB_PORT","5432")+"/"+cfg(config,"DB_NAME","tuplatform")+"?sslmode="+cfg(config,"DB_SSLMODE","require");
        var auth=new Properties();auth.setProperty("user",cfg(config,"DB_USERNAME",cfg(config,"DB_USER","")));auth.setProperty("password",cfg(config,"DB_PASSWORD",""));auth.setProperty("connectTimeout","5");auth.setProperty("socketTimeout","60");auth.setProperty("ApplicationName","linke-synthetic-demo-data");
        try(Connection db=DriverManager.getConnection(url,auth)) {
            db.setAutoCommit(false);
            require(count(db,"SELECT CASE WHEN current_database()=? THEN 1 ELSE 0 END",args[4])==1,"Actual database differs from explicit target; no writes");
            require(count(db,"SELECT count(*) FROM flyway_schema_history WHERE success AND version IN ('1','2','3','4','5')")==5,"Existing V1-V5 migrations required");
            if(action.equals("inspect")){summary(db,data);db.rollback();return;}
            if(action.equals("verify")){verify(db,data);summary(db,data);db.rollback();return;}
            // Serialize this batch without locking unrelated application data.
            try(var s=db.createStatement()){s.execute("SELECT pg_advisory_xact_lock(20261111,136)");s.execute("SET LOCAL lock_timeout='5s'");}
            if(action.equals("refresh-content")){RefreshDemoContent.run(db,data,Path.of(args[3]));return;}
            if(action.equals("remove")) {
                // Only exact deterministic fixture identities, never a LIKE-based blanket delete.
                for(var a:rows(data,"accounts")) {
                    UUID aid=id(account(a));
                    require(count(db,"SELECT count(*) FROM match_relationships WHERE requester_id=? OR tutor_id=?",aid,aid)==0,"Fixture has matching activity; retain it and review relationships before removal");
                    require(count(db,"SELECT count(*) FROM service_requests WHERE owner_id=? AND id NOT IN (SELECT unnest(?::uuid[]))",aid,db.createArrayOf("uuid",rows(data,"requests").stream().map(r->id(text(r,"key"))).toArray()))==0,"Fixture owns additional requests; retain it before manual review");
                }
                int removed=0;
                for(var a:rows(data,"accounts"))try(var s=db.prepareStatement("DELETE FROM accounts WHERE id=? AND account_key=? AND email=? AND NOT operator_granted")){s.setObject(1,id(account(a)));s.setString(2,account(a));s.setString(3,account(a)+DOMAIN);removed+=s.executeUpdate();}
                db.commit();System.out.println("REMOVED seed accounts="+removed+"; unrelated accounts retained");return;
            }
            OffsetDateTime now;try(var s=db.createStatement();var r=s.executeQuery("SELECT clock_timestamp()")){r.next();now=r.getObject(1,OffsetDateTime.class);}
            int existing=0;
            for(var a:rows(data,"accounts")) {
                int collision=count(db,"SELECT count(*) FROM accounts WHERE id=? OR account_key=? OR email_key=?",id(account(a)),account(a),account(a)+DOMAIN);
                if(collision>0){require(count(db,"SELECT count(*) FROM accounts WHERE id=? AND account_key=? AND email=? AND NOT operator_granted",id(account(a)),account(a),account(a)+DOMAIN)==1,"Fixture identity collision; nothing overwritten");existing++;}
            }
            if(existing>0){require(existing==data.path("accounts").size(),"Partial seed exists; no changes performed");verify(db,data);db.rollback();System.out.println("ALREADY_PRESENT; no data or passwords changed");return;}
            validate(data,now);
            Path output=Path.of(args[3]),credentials=output.resolve("credentials.local.json"),pending=output.resolve("credentials.pending.local.json");
            if(action.equals("apply")){require(!Files.exists(credentials)&&!Files.exists(pending),"Existing credential handoff retained; inspect before retrying");}
            var secrets=JSON.createArrayNode();
            for(var a:rows(data,"accounts")) {
                String name=account(a),password=SECRETS.token();
                update(db,"INSERT INTO accounts(id,account,account_key,email,email_key,password_hash,adult_confirmed_at,email_verified,business_identity,operator_granted,registration_flow_id,registration_context_hash) VALUES (?,?,?,?,?,?,?,true,?,false,?,?)",id(name),name,name,name+DOMAIN,name+DOMAIN,SECRETS.password(password),now,text(a,"identity"),id("flow:"+name),SECRETS.digest(BATCH+":"+name));
                var credential=secrets.addObject();credential.put("account",name);credential.put("password",password);credential.put("displayName",text(a,"displayName"));credential.put("identity",text(a,"identity"));
                var p=a.path("teacher");
                update(db,"INSERT INTO profiles(account_id,display_name,version,category_codes,service_modes,area_codes,introduction,price_min,price_max,availability_text,is_public) VALUES (?,?,?,CAST(? AS jsonb),CAST(? AS jsonb),CAST(? AS jsonb),?,?,?,?,?)",id(name),text(a,"displayName"),p.isMissingNode()?1L:2L,p.path("categoryCodes").isMissingNode()?"[]":encode(p.path("categoryCodes")),p.path("serviceModes").isMissingNode()?"[]":encode(p.path("serviceModes")),p.path("areaCodes").isMissingNode()?"[]":encode(p.path("areaCodes")),p.path("introduction").asText(""),p.path("priceMin").isTextual()?new java.math.BigDecimal(text(p,"priceMin")):null,p.path("priceMax").isTextual()?new java.math.BigDecimal(text(p,"priceMax")):null,p.path("availabilityText").asText(""),!p.isMissingNode());
            }
            int index=0;
            for(var r:rows(data,"requests")) {
                String key=text(r,"key"),state=text(r,"status");boolean draft=state.equals("DRAFT"),expired=state.equals("EXPIRED"),rejected=state.equals("REJECTED"),pendingReview=state.equals("PENDING_REVIEW");
                OffsetDateTime submitted=expired?now.minusDays(18):now.minusMinutes(index++*7L);
                OffsetDateTime expires=SubmissionRules.expiresAt(submitted);
                var fields=r.path("fields");UUID rid=id(key),sid=id("submission:"+key);
                update(db,"INSERT INTO service_requests(id,owner_id,status,version,fields,created_at,last_saved_at) VALUES (?,?,?,?,CAST(? AS jsonb),?,?)",rid,id(text(r,"owner")),state,draft?1L:pendingReview?2L:3L,encode(fields),submitted.minusHours(1),submitted);
                if(!draft) {
                    update(db,"INSERT INTO request_submissions(id,request_id,fields,publisher_display_name,publisher_declaration,declaration_version,submitted_at,expires_at,status,version) VALUES (?,?,CAST(? AS jsonb),?,?,'publisher-v1',?,?,?,?)",sid,rid,encode(fields),text(r,"publisherDisplayName"),text(r,"declaration"),submitted,expires,rejected?"REJECTED":pendingReview?"PENDING_REVIEW":"APPROVED",pendingReview?0L:1L);
                    update(db,"UPDATE service_requests SET latest_submission_id=? WHERE id=?",sid,rid);
                    if(!pendingReview)update(db,"INSERT INTO review_decisions(submission_id,reviewer_id,decision,reason,reviewed_at) VALUES (?,NULL,?,?,?)",sid,rejected?"REJECT":"APPROVE",rejected?"请补充具体学习目标和授课安排后重新提交。":null,submitted.plusMinutes(1));
                }
            }
            verify(db,data);summary(db,data);
            if(action.equals("dry-run")){db.rollback();System.out.println("DRY_RUN_ROLLED_BACK; no fixture data retained");return;}
            Files.createDirectories(output);
            Files.writeString(pending,JSON.writerWithDefaultPrettyPrinter().writeValueAsString(secrets),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
            db.commit();Files.move(pending,credentials);
            System.out.println("APPLIED; credentials saved in ignored .tools/demo-data/credentials.local.json; no passwords printed");
        }
    }
}
