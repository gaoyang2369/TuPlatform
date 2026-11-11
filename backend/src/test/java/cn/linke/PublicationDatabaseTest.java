package cn.linke;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="RUN_DATABASE_TESTS",matches="true")
@SpringBootTest(properties={"app.request-categories=DRAWING,EXTRACURRICULAR_STUDY"})
class PublicationDatabaseTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        String url=System.getenv("TEST_DB_URL");
        if(url==null||!url.matches("jdbc:postgresql://[^/]+/[A-Za-z0-9_]+_test(?:\\?.*)?"))throw new IllegalStateException("Dedicated _test database required");
        p.add("spring.datasource.url",()->url);p.add("spring.datasource.username",()->System.getenv("TEST_DB_USERNAME"));p.add("spring.datasource.password",()->System.getenv("TEST_DB_PASSWORD"));
    }
    @Autowired AuthService auth; @Autowired RequestDraftService drafts; @Autowired RequestPublicationService publishing;
    @Autowired JdbcTemplate db; @Autowired ObjectMapper json; @MockitoBean MailGateway mail;
    private String token;private final List<UUID> accounts=new ArrayList<>();
    private record User(UUID id,String session) {}
    @BeforeEach void setup(){when(mail.send(anyString(),anyString(),eq(false))).thenAnswer(call->{token=call.getArgument(1);return true;});}
    @AfterEach void cleanup(){for(UUID id:accounts)db.update("DELETE FROM service_requests WHERE owner_id=?",id);for(UUID id:accounts)db.update("DELETE FROM accounts WHERE id=?",id);}
    private User user(String identity,boolean operator){
        String name="pub_"+UUID.randomUUID().toString().replace("-","").substring(0,12),password="Synthetic publication password";
        Registration r=auth.register(new RegisterBody(name,password,name+"@example.invalid",true));auth.verify(new VerifyBody(token,null),r.context());
        var login=auth.login(new LoginBody(name,password));accounts.add(login.me().id());auth.identity(login.session(),new IdentityBody(identity));
        db.update("UPDATE accounts SET operator_granted=? WHERE id=?",operator,login.me().id());
        return new User(login.me().id(),login.session());
    }
    private SavedDraft ready(User user)throws Exception{
        db.update("UPDATE profiles SET display_name='提交时的展示名' WHERE account_id=?",user.id());
        SavedDraft d=drafts.create(user.session(),UUID.randomUUID().toString());
        return drafts.save(user.session(),d.id(),new DraftPatch(d.version(),(ObjectNode)json.readTree("""
            {"title":"合成公开需求","categoryCode":"DRAWING","audienceStageCode":"ADULT","areaCode":"PS_PINGSHAN","goal":"希望学习绘画基础",
             "budgetMin":"1000.01","budgetMax":"1800.99","durationHours":"1.5","frequency":{"mode":"PER_WEEK","count":2},
             "availableSlots":[{"weekday":1,"startMinute":600,"endMinute":1440}]}
            """)));
    }
    private RequestAction submit(User owner,SavedDraft draft){return publishing.submit(owner.session(),draft.id(),new SubmitRequest(draft.version(),"SELF_ADULT","publisher-v1"),UUID.randomUUID().toString());}
    private void approve(User operator,RequestAction submitted){publishing.decide(operator.session(),submitted.submissionId(),new ReviewRequest(0L,"APPROVE",null),UUID.randomUUID().toString());}
    @Test void submitIsCompletePrivateImmutableAndIdempotent()throws Exception{
        User owner=user("REQUESTER",false);SavedDraft d=ready(owner);String key=UUID.randomUUID().toString();var body=new SubmitRequest(d.version(),"SELF_ADULT","publisher-v1");
        var result=publishing.submit(owner.session(),d.id(),body,key);
        assertEquals(result,publishing.submit(owner.session(),d.id(),body,key));
        assertEquals(1,db.queryForObject("SELECT count(*) FROM request_submissions WHERE request_id=?",Integer.class,d.id()));
        assertEquals(409,assertThrows(ApiError.class,()->publishing.submit(owner.session(),d.id(),new SubmitRequest(d.version(),"GUARDIAN_OF_MINOR","publisher-v1"),key)).status);
        assertEquals(404,assertThrows(ApiError.class,()->publishing.publicDetail(d.id())).status);
        assertEquals(409,assertThrows(ApiError.class,()->drafts.save(owner.session(),d.id(),new DraftPatch(result.version(),json.createObjectNode().put("title","不能改待审")))).status);
        db.update("UPDATE profiles SET display_name='后来修改的名字' WHERE account_id=?",owner.id());
        assertEquals("提交时的展示名",drafts.get(owner.session(),d.id()).latestSubmission().publisherDisplayName());
        var times=db.queryForObject("SELECT submitted_at,expires_at FROM request_submissions WHERE id=?",(r,i)->new OffsetDateTime[]{r.getObject("submitted_at",OffsetDateTime.class),r.getObject("expires_at",OffsetDateTime.class)},result.submissionId());
        assertEquals(SubmissionRules.expiresAt(times[0]),times[1].withOffsetSameInstant(java.time.ZoneOffset.ofHours(8)));
    }
    @Test void missingProfileDeclarationAndForeignOwnersCannotSubmit()throws Exception{
        User owner=user("REQUESTER",false),other=user("REQUESTER",false),teacher=user("TUTOR",false);SavedDraft d=ready(owner);
        assertEquals(404,assertThrows(ApiError.class,()->publishing.submit(other.session(),d.id(),new SubmitRequest(d.version(),"SELF_ADULT","publisher-v1"),UUID.randomUUID().toString())).status);
        assertEquals(403,assertThrows(ApiError.class,()->publishing.submit(teacher.session(),d.id(),new SubmitRequest(d.version(),"SELF_ADULT","publisher-v1"),UUID.randomUUID().toString())).status);
        assertEquals(422,assertThrows(ApiError.class,()->publishing.submit(owner.session(),d.id(),new SubmitRequest(d.version(),null,null),UUID.randomUUID().toString())).status);
        db.update("UPDATE profiles SET display_name='' WHERE account_id=?",owner.id());
        assertEquals(422,assertThrows(ApiError.class,()->submit(owner,d)).status);
        assertEquals("DRAFT",drafts.get(owner.session(),d.id()).status());
    }
    @Test void operatorPermissionAndSelfReviewAreEnforced()throws Exception{
        User owner=user("REQUESTER",true),ordinary=user("TUTOR",false),operator=user("TUTOR",true);SavedDraft d=ready(owner);var submitted=submit(owner,d);
        assertEquals(403,assertThrows(ApiError.class,()->publishing.reviewDetail(ordinary.session(),submitted.submissionId())).status);
        assertEquals(404,assertThrows(ApiError.class,()->publishing.reviewDetail(owner.session(),submitted.submissionId())).status);
        assertTrue(((List<?>)publishing.reviewList(owner.session(),null,null).get("items")).isEmpty());
        assertEquals(404,assertThrows(ApiError.class,()->publishing.decide(owner.session(),submitted.submissionId(),new ReviewRequest(0L,"APPROVE",null),UUID.randomUUID().toString())).status);
        assertEquals(422,assertThrows(ApiError.class,()->publishing.decide(operator.session(),submitted.submissionId(),new ReviewRequest(0L,"REJECT","  "),UUID.randomUUID().toString())).status);
        assertEquals("PENDING_REVIEW",drafts.get(owner.session(),d.id()).status());
    }
    @Test void twoOperatorsHaveOnlyOneFinalDecision()throws Exception{
        User owner=user("REQUESTER",false),one=user("TUTOR",true),two=user("TUTOR",true);var submitted=submit(owner,ready(owner));
        CountDownLatch start=new CountDownLatch(1);ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> futures=new ArrayList<>();
            for(User reviewer:List.of(one,two))futures.add(pool.submit(()->{start.await();try{publishing.decide(reviewer.session(),submitted.submissionId(),new ReviewRequest(0L,"APPROVE",null),UUID.randomUUID().toString());return true;}catch(ApiError e){assertEquals(409,e.status);return false;}}));
            start.countDown();int successes=0;for(Future<Boolean> result:futures)if(result.get(25,TimeUnit.SECONDS))successes++;
            assertEquals(1,successes);assertEquals(1,db.queryForObject("SELECT count(*) FROM review_decisions WHERE submission_id=?",Integer.class,submitted.submissionId()));
        }finally{pool.shutdownNow();}
        assertEquals("OPEN",drafts.get(owner.session(),submitted.id()).status());
    }
    @Test void rejectionRevisionAndFreshDeclarationPreservePriorSnapshot()throws Exception{
        User owner=user("REQUESTER",false),operator=user("TUTOR",true);var submitted=submit(owner,ready(owner));String reviewKey=UUID.randomUUID().toString();var decision=new ReviewRequest(0L,"REJECT","请补充目标说明");
        var rejected=publishing.decide(operator.session(),submitted.submissionId(),decision,reviewKey);
        assertEquals(rejected,publishing.decide(operator.session(),submitted.submissionId(),decision,reviewKey));
        String revisionKey=UUID.randomUUID().toString();var revised=publishing.revise(owner.session(),submitted.id(),new VersionBody(rejected.version()),revisionKey);
        assertEquals(revised,publishing.revise(owner.session(),submitted.id(),new VersionBody(rejected.version()),revisionKey));
        SavedDraft changed=drafts.save(owner.session(),submitted.id(),new DraftPatch(revised.version(),json.createObjectNode().put("goal","已补充的新目标")));
        assertEquals(422,assertThrows(ApiError.class,()->publishing.submit(owner.session(),changed.id(),new SubmitRequest(changed.version(),null,null),UUID.randomUUID().toString())).status);
        var next=submit(owner,changed);assertNotEquals(submitted.submissionId(),next.submissionId());
        assertEquals("请补充目标说明",publishing.reviewDetail(operator.session(),next.submissionId()).priorReviewFeedback());
        assertEquals("希望学习绘画基础",publishing.reviewDetail(operator.session(),submitted.submissionId()).fields().path("goal").asText());
        assertEquals(2,db.queryForObject("SELECT count(*) FROM request_submissions WHERE request_id=?",Integer.class,changed.id()));
    }
    @Test void publicDtoUsesApprovedSnapshotAndExpiryFiltersImmediately()throws Exception{
        User owner=user("REQUESTER",false),operator=user("TUTOR",true);var submitted=submit(owner,ready(owner));approve(operator,submitted);
        db.update("UPDATE profiles SET display_name='后来修改的名字' WHERE account_id=?",owner.id());
        Map<String,Object> publicValue=publishing.publicDetail(submitted.id());assertEquals("提交时的展示名",publicValue.get("publisherDisplayName"));
        for(String forbidden:List.of("email","phone","wechat","ownerId","publisherDeclaration","declarationVersion","expiresAt","submittedAt","reviewFeedback","version","status","fields"))assertFalse(publicValue.containsKey(forbidden),forbidden);
        assertEquals("1000.01",publicValue.get("budgetMin"));
        assertEquals(1,((List<?>)publishing.publicList(null,"DRAWING","PS_PINGSHAN",null).get("items")).size());
        assertEquals(1,((List<?>)publishing.publicList(null,null,null,null,"坪山街道").get("items")).size());
        db.update("UPDATE request_submissions SET submitted_at=clock_timestamp()-interval '16 days',expires_at=clock_timestamp() WHERE id=?",submitted.submissionId());
        assertEquals("EXPIRED",drafts.get(owner.session(),submitted.id()).status());
        assertEquals(404,assertThrows(ApiError.class,()->publishing.publicDetail(submitted.id())).status);
        assertTrue(((List<?>)publishing.publicList(null,null,null,null).get("items")).isEmpty());
        var revision=publishing.revise(owner.session(),submitted.id(),new VersionBody(drafts.get(owner.session(),submitted.id()).version()),UUID.randomUUID().toString());assertEquals("DRAFT",revision.status());
    }
    @Test void expiredPendingSubmissionCannotBeApprovedAndClosedCategoryStopsVisibility()throws Exception{
        User owner=user("REQUESTER",false),operator=user("TUTOR",true);var submitted=submit(owner,ready(owner));
        db.update("UPDATE request_submissions SET submitted_at=clock_timestamp()-interval '16 days',expires_at=clock_timestamp() WHERE id=?",submitted.submissionId());
        assertEquals(409,assertThrows(ApiError.class,()->approve(operator,submitted)).status);
        assertTrue(((List<?>)publishing.reviewList(operator.session(),null,null).get("items")).isEmpty());
        assertEquals("EXPIRED",publishing.reviewDetail(operator.session(),submitted.submissionId()).status());
        var another=submit(owner,ready(owner));approve(operator,another);
        var closedDrafts=new RequestDraftService(db,auth,json,"");var closed=new RequestPublicationService(db,auth,closedDrafts,json,new Secrets());
        assertEquals(404,assertThrows(ApiError.class,()->closed.publicDetail(another.id())).status);
        assertTrue(((List<?>)closed.publicList(null,null,null,null).get("items")).isEmpty());
    }
    @Test void communityStudyAndBroadTimeSurviveSaveSubmissionReviewAndPublicSearch() throws Exception {
        User owner=user("REQUESTER",false),operator=user("TUTOR",true);SavedDraft original=ready(owner);
        String location="坪山区 · 合成花园"+UUID.randomUUID().toString().substring(0,8);
        ObjectNode changes=json.createObjectNode().put("categoryCode","EXTRACURRICULAR_STUDY").put("grade","成人学习").put("subject","英语").put("locationText",location).putNull("areaCode");
        changes.putArray("availableSlots");
        ObjectNode preference=changes.putObject("availabilityPreference").put("mode","PREFERRED");
        preference.putArray("weekdays").add(6).add(7);preference.putArray("periods").add("AFTERNOON");
        SavedDraft saved=drafts.save(owner.session(),original.id(),new DraftPatch(original.version(),changes));
        assertEquals(location,drafts.get(owner.session(),saved.id()).fields().path("locationText").asText());
        var submitted=submit(owner,saved);
        assertEquals("英语",publishing.reviewDetail(operator.session(),submitted.submissionId()).fields().path("subject").asText());
        approve(operator,submitted);
        var visible=publishing.publicDetail(saved.id());
        assertEquals(location,visible.get("locationText"));assertEquals("成人学习",visible.get("grade"));assertEquals("英语",visible.get("subject"));
        assertEquals("PREFERRED",((Map<?,?>)visible.get("availabilityPreference")).get("mode"));
        assertTrue(((List<?>)visible.get("availableSlots")).isEmpty());
        assertEquals(1,((List<?>)publishing.publicList(null,null,null,null,location).get("items")).size());
        assertTrue(((List<?>)publishing.publicList(null,null,null,null,"不存在"+location).get("items")).isEmpty());
    }
    @Test void publicPagesHaveTenDistinctItemsAndSupportDistrictGradeAndMultipleSubjects() {
        User owner=user("REQUESTER",false);
        for(int i=0;i<12;i++) {
            UUID request=UUID.randomUUID(),submission=UUID.randomUUID();
            ObjectNode fields=json.createObjectNode().put("title","分页合成需求"+i).put("categoryCode","EXTRACURRICULAR_STUDY").put("grade",i<6?"高一":"高二").put("subject",i<6?"数学、物理":"英语").put("serviceMode","OFFLINE");
            if(i%2==0)fields.put("districtCode","SZ_PINGSHAN"); // Legacy records use the existing Pingshan service scope.
            db.update("INSERT INTO service_requests(id,owner_id,status,fields) VALUES (?,?,'OPEN',CAST(? AS jsonb))",request,owner.id(),fields.toString());
            db.update("INSERT INTO request_submissions(id,request_id,fields,publisher_display_name,publisher_declaration,declaration_version,submitted_at,expires_at,status) VALUES (?,?,CAST(? AS jsonb),'合成需求方','GUARDIAN_OF_MINOR','publisher-v1',now(),now()+interval '15 days','APPROVED')",submission,request,fields.toString());
            db.update("UPDATE service_requests SET latest_submission_id=? WHERE id=?",submission,request);
        }
        var first=publishing.publicList(null,null,null,null,null,"SZ_PINGSHAN",null,null);
        List<?> one=(List<?>)first.get("items");assertEquals(10,one.size());assertNotNull(first.get("nextCursor"));
        var second=publishing.publicList((String)first.get("nextCursor"),null,null,null,null,"SZ_PINGSHAN",null,null);
        List<?> two=(List<?>)second.get("items");assertEquals(2,two.size());assertNull(second.get("nextCursor"));
        Set<Object> ids=new HashSet<>();for(Object item:one)assertTrue(ids.add(((Map<?,?>)item).get("id")));for(Object item:two)assertTrue(ids.add(((Map<?,?>)item).get("id")));
        assertEquals(6,((List<?>)publishing.publicList(null,null,null,"OFFLINE",null,"SZ_PINGSHAN","高一","物理").get("items")).size());
        assertTrue(((List<?>)publishing.publicList(null,null,null,null,null,"SZ_PINGSHAN","高一","英语").get("items")).isEmpty());
        assertEquals(422,assertThrows(ApiError.class,()->publishing.publicList(null,null,null,null,null,"NOT_OPEN",null,null)).status);
    }
}
