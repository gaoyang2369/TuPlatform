package cn.linke;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="RUN_DATABASE_TESTS",matches="true")
@SpringBootTest(properties={"app.request-categories=DRAWING","app.teacher-categories=DRAWING"})
class MatchDatabaseTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        String url=System.getenv("TEST_DB_URL");
        if(url==null||!url.matches("jdbc:postgresql://[^/]+/[A-Za-z0-9_]+_test(?:\\?.*)?"))throw new IllegalStateException("Dedicated _test database required");
        p.add("spring.datasource.url",()->url);p.add("spring.datasource.username",()->System.getenv("TEST_DB_USERNAME"));p.add("spring.datasource.password",()->System.getenv("TEST_DB_PASSWORD"));
    }
    @Autowired MatchService matches; @Autowired ProfileService profiles; @Autowired RequestDraftService drafts;
    @Autowired RequestPublicationService publication; @Autowired JdbcTemplate db; @Autowired ObjectMapper json; @Autowired Secrets secrets;
    @Autowired ContactService contacts;
    @MockitoBean MailGateway mail;
    private final List<UUID> accounts=new ArrayList<>();
    private record User(UUID id,String session) {}
    private record Setup(User owner,User tutor,User operator,UUID request,UUID submission,long teacherVersion) {}
    private String key(){return UUID.randomUUID().toString();}
    private User user(String role) {
        UUID id=UUID.randomUUID();String name="test_match_"+id.toString().replace("-","").substring(0,18),session=secrets.token();
        db.update("INSERT INTO accounts(id,account,account_key,email,email_key,password_hash,adult_confirmed_at,email_verified,business_identity,registration_flow_id,registration_context_hash) VALUES(?,?,?,?,?,'synthetic-no-login',now(),true,?,?,?)",id,name,name,name+"@example.invalid",name+"@example.invalid",role,UUID.randomUUID(),secrets.digest(key()));
        db.update("INSERT INTO profiles(account_id,display_name) VALUES(?,'合成展示名')",id);
        db.update("INSERT INTO auth_sessions(token_hash,account_id,expires_at) VALUES(?,?,now()+interval '1 hour')",secrets.digest(session),id);
        accounts.add(id);return new User(id,session);
    }
    private long publish(User tutor) {
        var p=profiles.get(tutor.session());
        var saved=profiles.saveTeacher(tutor.session(),new TeacherBody(p.version(),"当时老师",List.of("DRAWING"),List.of("ONLINE"),List.of(),"合成介绍",null,null,""));
        return profiles.visibility(tutor.session(),new VersionBody(saved.version()),true,key()).version();
    }
    private Setup setup() throws Exception {
        User owner=user("REQUESTER"),tutor=user("TUTOR"),operator=user("TUTOR");
        db.update("UPDATE accounts SET operator_granted=true WHERE id=?",operator.id());long version=publish(tutor);
        var draft=drafts.create(owner.session(),key());
        var saved=drafts.save(owner.session(),draft.id(),new DraftPatch(draft.version(),(ObjectNode)json.readTree("""
            {"title":"合成撮合需求","categoryCode":"DRAWING","audienceStageCode":"ADULT","areaCode":"PS_PINGSHAN","goal":"当时目标",
             "budgetMin":"100","budgetMax":"200","durationHours":"1.5","frequency":{"mode":"NEGOTIABLE"},"availableSlots":[{"weekday":1,"startMinute":600,"endMinute":660}]}
            """)));
        var submitted=publication.submit(owner.session(),draft.id(),new SubmitRequest(saved.version(),"SELF_ADULT","publisher-v1"),key());
        publication.decide(operator.session(),submitted.submissionId(),new ReviewRequest(0L,"APPROVE",null),key());
        return new Setup(owner,tutor,operator,draft.id(),submitted.submissionId(),version);
    }
    private StartMatch body(Setup s,boolean invite){return new StartMatch(s.request(),s.submission(),invite?"INVITATION":"APPLICATION",invite?s.tutor().id():null,s.teacherVersion());}
    private MatchView start(Setup s,boolean invite){return matches.start(invite?s.owner().session():s.tutor().session(),body(s,invite),key()).match();}
    private RespondMatch response(MatchView m){return new RespondMatch(m.roundId(),m.version());}
    private MatchView act(User user,MatchView m,String action){return matches.respond(user.session(),m.id(),response(m),key(),action);}
    private void expire(Setup s){db.update("UPDATE request_submissions SET submitted_at=clock_timestamp()-interval '16 days',expires_at=clock_timestamp() WHERE id=?",s.submission());}
    private StartMatch direct(User tutor,long version,String message){return new StartMatch(null,null,"DIRECT_INVITATION",tutor.id(),version,message);}
    @Test void directInvitationNeedsNoRequestAndCapturesOnlyNameAndOptionalMessage() throws Exception {
        var owner=user("REQUESTER");var tutor=user("TUTOR");long version=publish(tutor);String receipt=key();var body=direct(tutor,version,"  想了解周末课程  ");
        var result=matches.start(owner.session(),body,receipt);var m=result.match();
        assertNull(m.requestId());assertEquals("WAITING",m.state());assertFalse(m.requestExpired());assertFalse(m.submissionChanged());
        assertEquals(Map.of("title","直接邀请","publisherDisplayName","合成展示名","introduction","想了解周末课程"),m.submissionSnapshot());
        assertEquals(0,db.queryForObject("SELECT count(*) FROM service_requests WHERE owner_id=?",Integer.class,owner.id()));
        assertEquals(result,matches.start(owner.session(),body,receipt));
        drafts.create(owner.session(),key()); // Even a private/unapproved draft must not affect direct contact.
        assertEquals("EXISTING",matches.start(owner.session(),direct(tutor,version,""),key()).outcome());
        assertTrue(matches.detail(tutor.session(),m.id()).waitingForMe());
        assertEquals(1,((List<?>)matches.list(tutor.session(),null,"WAITING",null).get("items")).size());
        var outsider=user("REQUESTER");assertEquals(404,assertThrows(ApiError.class,()->matches.detail(outsider.session(),m.id())).status);
        assertEquals(403,assertThrows(ApiError.class,()->act(owner,m,"ACCEPT")).status);
        assertEquals("MUTUAL",act(tutor,m,"ACCEPT").state());
        assertEquals("EXISTING",matches.start(owner.session(),body,key()).outcome());
        assertFalse(json.writeValueAsString(m).contains("@example.invalid"));
    }
    @Test void directInvitationValidatesRoleTeacherVersionVisibilityAndMessage() {
        var owner=user("REQUESTER");var tutor=user("TUTOR");long version=publish(tutor);
        assertEquals(403,assertThrows(ApiError.class,()->matches.start(tutor.session(),direct(tutor,version,""),key())).status);
        assertEquals("TEACHER_PROFILE_CHANGED",assertThrows(ApiError.class,()->matches.start(owner.session(),direct(tutor,version-1,""),key())).code);
        assertEquals(422,assertThrows(ApiError.class,()->matches.start(owner.session(),direct(tutor,version,"字".repeat(501)),key())).status);
        assertEquals(422,assertThrows(ApiError.class,()->matches.start(owner.session(),new StartMatch(UUID.randomUUID(),null,"DIRECT_INVITATION",tutor.id(),version),key())).status);
        db.update("UPDATE profiles SET display_name='' WHERE account_id=?",owner.id());
        assertEquals("PROFILE_REQUIRED",assertThrows(ApiError.class,()->matches.start(owner.session(),direct(tutor,version,""),key())).code);
        profiles.visibility(tutor.session(),new VersionBody(version),false,key());
        assertEquals("PROFILE_NOT_PUBLIC",assertThrows(ApiError.class,()->matches.start(owner.session(),direct(tutor,version,""),key())).code);
    }
    @Test void directWithdrawalCreatesFreshRoundWhileRejectionBlocksRepeatInvites() {
        var owner=user("REQUESTER");var tutor=user("TUTOR");long version=publish(tutor);var body=direct(tutor,version,null);
        var first=matches.start(owner.session(),body,key()).match();act(owner,first,"WITHDRAW");
        assertTrue(matches.detail(owner.session(),first.id()).canInitiateAgain());assertFalse(matches.detail(tutor.session(),first.id()).canInitiateAgain());
        var second=matches.start(owner.session(),body,key()).match();assertEquals(first.id(),second.id());assertNotEquals(first.roundId(),second.roundId());
        assertEquals(409,assertThrows(ApiError.class,()->act(tutor,first,"ACCEPT")).status);
        act(tutor,second,"REJECT");assertFalse(matches.detail(owner.session(),second.id()).canInitiateAgain());
        assertEquals("REAPPLY_BLOCKED",assertThrows(ApiError.class,()->matches.start(owner.session(),body,key())).code);
    }
    @Test void concurrentDirectInvitesAndExistingRequestIntentionDoNotDuplicateActiveContact() throws Exception {
        var owner=user("REQUESTER");var tutor=user("TUTOR");long version=publish(tutor);var body=direct(tutor,version,"");
        var results=race(()->matches.start(owner.session(),body,key()),()->matches.start(owner.session(),body,key()));
        assertEquals(Set.of("CREATED","EXISTING"),new HashSet<>(results.stream().map(MatchStartResult::outcome).toList()));
        assertEquals(results.getFirst().match().id(),results.getLast().match().id());
        var s=setup();var application=start(s,false);
        var found=matches.start(s.owner().session(),direct(s.tutor(),s.teacherVersion(),""),key());
        assertEquals("EXISTING",found.outcome());assertEquals(application.id(),found.match().id());assertTrue(found.match().waitingForMe());
        var reverse=setup();var invitation=matches.start(reverse.owner().session(),direct(reverse.tutor(),reverse.teacherVersion(),""),key()).match();
        assertEquals(invitation.id(),start(reverse,false).id());assertEquals("WAITING",matches.detail(reverse.tutor().session(),invitation.id()).state());
    }
    @Test void directInvitationKeepsBilateralContactConsentWithdrawalAndReconfirmation() {
        var owner=user("REQUESTER");var tutor=user("TUTOR");long version=publish(tutor);
        var m=matches.start(owner.session(),direct(tutor,version,""),key()).match();
        contacts.save(owner.session(),new SaveContacts(0L,null,"synthetic_owner"));contacts.save(tutor.session(),new SaveContacts(0L,null,"synthetic_teacher"));
        var ownerBody=new AgreeContacts(m.roundId(),0L,List.of("WECHAT"),contacts.get(owner.session()).fieldVersions(),true);
        assertEquals("NOT_MUTUAL",assertThrows(ApiError.class,()->contacts.agree(owner.session(),m.id(),ownerBody,key())).code);
        profiles.visibility(tutor.session(),new VersionBody(version),false,key()); // Existing invitation can still be accepted.
        act(tutor,m,"ACCEPT");var one=contacts.agree(owner.session(),m.id(),ownerBody,key());assertEquals(false,one.get("canViewContacts"));
        var both=contacts.agree(tutor.session(),m.id(),new AgreeContacts(m.roundId(),1L,List.of("WECHAT"),contacts.get(tutor.session()).fieldVersions(),true),key());
        assertEquals(true,both.get("canViewContacts"));
        contacts.save(owner.session(),new SaveContacts(1L,null,"synthetic_changed"));
        var hidden=contacts.grant(tutor.session(),m.id());assertEquals(false,hidden.get("canViewContacts"));assertEquals(true,hidden.get("requiresReconfirmation"));assertFalse(hidden.containsKey("otherSharedContacts"));
        var withdrawn=contacts.withdraw(tutor.session(),m.id(),new WithdrawContacts(m.roundId(),((Number)hidden.get("version")).longValue()),key());assertEquals(false,withdrawn.get("canViewContacts"));
    }
    @AfterEach void cleanup(){
        for(UUID id:accounts)db.update("DELETE FROM match_relationships WHERE requester_id=? OR tutor_id=?",id,id);
        for(UUID id:accounts)db.update("DELETE FROM service_requests WHERE owner_id=?",id);
        for(UUID id:accounts)db.update("DELETE FROM accounts WHERE id=?",id);
    }
    @Test void discoveryRequiresRequesterAndOnlyShowsPublishedAllowlistedFields() throws Exception {
        var s=setup();
        assertEquals(401,assertThrows(ApiError.class,()->profiles.publicTeachers(null,null,null,null,null)).status);
        assertEquals(403,assertThrows(ApiError.class,()->profiles.publicTeachers(s.tutor().session(),null,null,null,null)).status);
        var page=profiles.publicTeachers(s.owner().session(),null,"DRAWING",null,"ONLINE");assertEquals(1,((List<?>)page.get("items")).size());
        var projection=profiles.publicTeacher(s.owner().session(),s.tutor().id());
        assertEquals(Set.of("id","profileVersion","displayName","categoryCodes","serviceModes","areaCodes","introduction","priceMin","priceMax","availabilityText"),projection.keySet());
        profiles.visibility(s.tutor().session(),new VersionBody(s.teacherVersion()),false,key());
        assertTrue(((List<?>)profiles.publicTeachers(s.owner().session(),null,null,null,null).get("items")).isEmpty());
        assertEquals(404,assertThrows(ApiError.class,()->profiles.publicTeacher(s.owner().session(),s.tutor().id())).status);
    }
    @SuppressWarnings("unchecked")
    @Test void teacherDiscoveryPagesTenProfilesWithoutDuplicatesOrPrivateFields() {
        var owner=user("REQUESTER");var expected=new HashSet<UUID>();
        for(int i=0;i<23;i++){
            var tutor=user("TUTOR");expected.add(tutor.id());
            db.update("UPDATE profiles SET display_name=?,category_codes='[\"DRAWING\"]'::jsonb,service_modes='[\"ONLINE\"]'::jsonb,introduction=?,is_public=true,price_min=100,price_max=150 WHERE account_id=?",
                "分页合成老师"+i,"方向：成人水彩入门。\n擅长：色彩与构图。",tutor.id());
        }
        var hidden=user("TUTOR");
        var unverified=user("TUTOR");
        db.update("UPDATE profiles SET display_name='未验证合成老师',category_codes='[\"DRAWING\"]'::jsonb,service_modes='[\"ONLINE\"]'::jsonb,introduction='合成介绍',is_public=true WHERE account_id=?",unverified.id());
        db.update("UPDATE accounts SET email_verified=false WHERE id=?",unverified.id());
        var found=new HashSet<UUID>();String cursor=null;
        for(int size:List.of(10,10,3)){
            var page=profiles.publicTeachers(owner.session(),cursor,"DRAWING",null,"ONLINE");
            var teachers=(List<Map<String,Object>>)page.get("items");assertEquals(size,teachers.size());
            for(var teacher:teachers){
                UUID id=(UUID)teacher.get("id");assertTrue(found.add(id));assertTrue(expected.contains(id));
                assertEquals(profiles.publicTeacher(owner.session(),id),teacher);
                assertEquals("方向：成人水彩入门。\n擅长：色彩与构图。",teacher.get("introduction"));
                for(String field:List.of("email","phone","wechat","account","password_hash","is_public"))assertFalse(teacher.containsKey(field));
            }
            cursor=(String)page.get("nextCursor");if(size==10)assertEquals(teachers.getLast().get("id").toString(),cursor);else assertNull(cursor);
        }
        assertEquals(expected,found);assertFalse(found.contains(hidden.id()));assertFalse(found.contains(unverified.id()));
        assertTrue(((List<?>)profiles.publicTeachers(owner.session(),null,"DRAWING",null,"OFFLINE").get("items")).isEmpty());
        assertEquals(422,assertThrows(ApiError.class,()->profiles.publicTeachers(owner.session(),"invalid-cursor",null,null,null)).status);
    }
    @Test void duplicateReverseAndIdempotentStartsNeverImplicitlyAccept() throws Exception {
        var s=setup();String key=key();var initial=matches.start(s.tutor().session(),body(s,false),key);
        assertEquals(initial,matches.start(s.tutor().session(),body(s,false),key));
        var reverse=matches.start(s.owner().session(),body(s,true),key());assertEquals("EXISTING",reverse.outcome());assertEquals(initial.match().id(),reverse.match().id());assertEquals("WAITING",reverse.match().state());assertTrue(reverse.match().waitingForMe());
        assertEquals("EXISTING",matches.start(s.tutor().session(),body(s,false),key()).outcome());
        assertEquals(409,assertThrows(ApiError.class,()->matches.start(s.tutor().session(),new StartMatch(s.request(),UUID.randomUUID(),"APPLICATION",null,s.teacherVersion()),key)).status);
        assertEquals(1,db.queryForObject("SELECT count(*) FROM match_rounds WHERE match_id=?",Integer.class,initial.match().id()));
    }
    @Test void responsePermissionsVersionsAndReceiptsAreIndependentOfClient() throws Exception {
        var s=setup();var m=start(s,false);var outsider=user("REQUESTER");
        assertEquals(404,assertThrows(ApiError.class,()->matches.detail(outsider.session(),m.id())).status);
        assertEquals(404,assertThrows(ApiError.class,()->act(outsider,m,"ACCEPT")).status);
        assertTrue(((List<?>)matches.list(outsider.session(),null,null,s.request()).get("items")).isEmpty());
        assertEquals(403,assertThrows(ApiError.class,()->act(s.tutor(),m,"ACCEPT")).status);
        assertEquals(403,assertThrows(ApiError.class,()->act(s.owner(),m,"WITHDRAW")).status);
        String key=key();var accepted=matches.respond(s.owner().session(),m.id(),response(m),key,"ACCEPT");assertEquals("MUTUAL",accepted.state());
        assertEquals(accepted,matches.respond(s.owner().session(),m.id(),response(m),key,"ACCEPT"));
        assertEquals(409,assertThrows(ApiError.class,()->act(s.tutor(),m,"WITHDRAW")).status);
        assertFalse(accepted.canWithdraw());assertFalse(accepted.canInitiateAgain());
        String encoded=json.writeValueAsString(accepted);for(String privateName:List.of("publisherDeclaration","expiresAt","email","phone","wechat","reviewFeedback"))assertFalse(encoded.contains('"'+privateName+'"'),privateName);
    }
    @Test void rejectionRestrictionPersistsAcrossLaterWithdrawnRounds() throws Exception {
        var s=setup();var m=start(s,false);act(s.owner(),m,"REJECT");
        assertFalse(matches.detail(s.tutor().session(),m.id()).canInitiateAgain());assertTrue(matches.detail(s.owner().session(),m.id()).canInitiateAgain());
        assertEquals("REAPPLY_BLOCKED",assertThrows(ApiError.class,()->start(s,false)).code);
        var again=start(s,true);assertEquals(m.id(),again.id());assertNotEquals(m.roundId(),again.roundId());assertEquals("WAITING",again.state());
        act(s.owner(),again,"WITHDRAW");assertEquals("REAPPLY_BLOCKED",assertThrows(ApiError.class,()->start(s,false)).code);
        assertEquals(409,assertThrows(ApiError.class,()->act(s.tutor(),m,"ACCEPT")).status);
    }
    @Test void withdrawalStartsFreshRoundWithoutOldResponse() throws Exception {
        var s=setup();var m=start(s,true);act(s.owner(),m,"WITHDRAW");var next=start(s,false);
        assertEquals(m.id(),next.id());assertNotEquals(m.roundId(),next.roundId());assertEquals("WAITING",next.state());assertFalse(next.waitingForMe());
        assertEquals(409,assertThrows(ApiError.class,()->act(s.tutor(),m,"ACCEPT")).status);
    }
    @Test void expirationAndUnpublishingBlockNewRoundsButKeepSnapshotsAndResponses() throws Exception {
        var s=setup();var m=start(s,true);expire(s);
        profiles.visibility(s.tutor().session(),new VersionBody(s.teacherVersion()),false,key());
        var current=matches.detail(s.tutor().session(),m.id());assertTrue(current.requestExpired());assertFalse(current.teacherPublic());
        assertEquals("合成撮合需求",current.submissionSnapshot().get("title"));assertEquals("当时老师",current.teacherSnapshot().get("displayName"));
        assertEquals("EXISTING",matches.start(s.owner().session(),body(s,true),key()).outcome());
        act(s.tutor(),current,"REJECT");assertEquals("REQUEST_EXPIRED",assertThrows(ApiError.class,()->start(s,true)).code);
        assertFalse(matches.detail(s.owner().session(),m.id()).canInitiateAgain());
    }
    @Test void staleTeacherOrSubmissionMustBeReadAndNewIntentRequiresPublication() throws Exception {
        var s=setup();
        assertEquals("SUBMISSION_CHANGED",assertThrows(ApiError.class,()->matches.start(s.tutor().session(),new StartMatch(s.request(),UUID.randomUUID(),"APPLICATION",null,s.teacherVersion()),key())).code);
        assertEquals("TEACHER_PROFILE_CHANGED",assertThrows(ApiError.class,()->matches.start(s.tutor().session(),new StartMatch(s.request(),s.submission(),"APPLICATION",null,s.teacherVersion()-1),key())).code);
        profiles.visibility(s.tutor().session(),new VersionBody(s.teacherVersion()),false,key());
        assertEquals("PROFILE_NOT_PUBLIC",assertThrows(ApiError.class,()->start(s,false)).code);
        var other=user("REQUESTER");assertEquals(404,assertThrows(ApiError.class,()->matches.start(other.session(),body(s,true),key())).status);
        assertEquals(403,assertThrows(ApiError.class,()->matches.start(s.owner().session(),body(s,false),key())).status);
        assertEquals(422,assertThrows(ApiError.class,()->matches.start(s.tutor().session(),new StartMatch(s.request(),s.submission(),"APPLICATION",s.tutor().id(),s.teacherVersion()),key())).status);
        var privateDraft=drafts.create(s.owner().session(),key());
        assertEquals(404,assertThrows(ApiError.class,()->matches.start(s.tutor().session(),new StartMatch(privateDraft.id(),UUID.randomUUID(),"APPLICATION",null,s.teacherVersion()),key())).status);
    }
    @Test void revisionCleanupPreservesOriginalRelationshipAndNewSubmissionDoesNotCloneIt() throws Exception {
        var s=setup();var m=start(s,false);expire(s);var current=drafts.get(s.owner().session(),s.request());
        publication.revise(s.owner().session(),s.request(),new VersionBody(current.version()),key());
        db.update("UPDATE service_requests SET last_saved_at=now()-interval '91 days' WHERE id=?",s.request());drafts.cleanupExpiredDrafts();
        assertEquals("WAITING",matches.detail(s.owner().session(),m.id()).state());assertEquals(1,db.queryForObject("SELECT count(*) FROM service_requests WHERE id=?",Integer.class,s.request()));
        db.update("UPDATE service_requests SET last_saved_at=now() WHERE id=?",s.request());var revised=drafts.get(s.owner().session(),s.request());
        var submitted=publication.submit(s.owner().session(),s.request(),new SubmitRequest(revised.version(),"SELF_ADULT","publisher-v1"),key());publication.decide(s.operator().session(),submitted.submissionId(),new ReviewRequest(0L,"APPROVE",null),key());
        var result=matches.start(s.tutor().session(),new StartMatch(s.request(),submitted.submissionId(),"APPLICATION",null,s.teacherVersion()),key());
        assertEquals("EXISTING",result.outcome());assertEquals(s.submission().toString(),result.match().submissionSnapshot().get("submissionId"));assertTrue(result.match().submissionChanged());
        assertEquals("MUTUAL",act(s.owner(),m,"ACCEPT").state());
    }
    private <T> List<T> race(Supplier<T> one,Supplier<T> two) throws Exception {
        var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try{var a=pool.submit(()->{gate.await();return one.get();});var b=pool.submit(()->{gate.await();return two.get();});gate.countDown();return List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));}finally{pool.shutdownNow();}
    }
    @Test void simultaneousOppositeStartsProduceOneWaitingRound() throws Exception {
        var s=setup();var results=race(()->matches.start(s.owner().session(),body(s,true),key()),()->matches.start(s.tutor().session(),body(s,false),key()));
        assertEquals(results.getFirst().match().id(),results.getLast().match().id());assertEquals(Set.of("CREATED","EXISTING"),new HashSet<>(results.stream().map(MatchStartResult::outcome).toList()));assertEquals("WAITING",results.getLast().match().state());
        assertEquals(1,db.queryForObject("SELECT count(*) FROM match_rounds WHERE match_id=?",Integer.class,results.getFirst().match().id()));
    }
    @Test void acceptanceVersusWithdrawalHasOneWinner() throws Exception {
        var s=setup();var m=start(s,false);
        Supplier<String> accept=()->{try{return act(s.owner(),m,"ACCEPT").state();}catch(ApiError e){assertEquals(409,e.status);return "CONFLICT";}};
        Supplier<String> withdraw=()->{try{return act(s.tutor(),m,"WITHDRAW").state();}catch(ApiError e){assertEquals(409,e.status);return "CONFLICT";}};
        var results=race(accept,withdraw);assertEquals(1,results.stream().filter("CONFLICT"::equals).count());
        assertTrue(Set.of("MUTUAL","WITHDRAWN").contains(matches.detail(s.owner().session(),m.id()).state()));
    }
    @Test void newApprovedSubmissionAllowsRejectedActorAndCapturesFreshSnapshots() throws Exception {
        var s=setup();var original=start(s,false);act(s.owner(),original,"REJECT");
        var changed=profiles.saveTeacher(s.tutor().session(),new TeacherBody(s.teacherVersion(),"最新老师名字",List.of("DRAWING"),List.of("ONLINE"),List.of(),"最新自述",null,null,""));
        var historical=matches.detail(s.owner().session(),original.id());
        assertEquals("当时老师",historical.teacherSnapshot().get("displayName"));assertTrue(historical.teacherProfileChanged());
        expire(s);var expired=drafts.get(s.owner().session(),s.request());
        publication.revise(s.owner().session(),s.request(),new VersionBody(expired.version()),key());
        var draft=drafts.get(s.owner().session(),s.request());
        var saved=drafts.save(s.owner().session(),s.request(),new DraftPatch(draft.version(),json.createObjectNode().put("goal","新版目标")));
        var submitted=publication.submit(s.owner().session(),s.request(),new SubmitRequest(saved.version(),"SELF_ADULT","publisher-v1"),key());
        publication.decide(s.operator().session(),submitted.submissionId(),new ReviewRequest(0L,"APPROVE",null),key());
        assertTrue(matches.detail(s.tutor().session(),original.id()).canInitiateAgain());
        var next=matches.start(s.tutor().session(),new StartMatch(s.request(),submitted.submissionId(),"APPLICATION",null,changed.version()),key()).match();
        assertEquals(original.id(),next.id());assertNotEquals(original.roundId(),next.roundId());assertEquals("WAITING",next.state());
        assertEquals("新版目标",next.submissionSnapshot().get("goal"));assertEquals("最新老师名字",next.teacherSnapshot().get("displayName"));
        assertEquals("当时老师",db.queryForObject("SELECT teacher_snapshot->>'displayName' FROM match_rounds WHERE id=?",String.class,original.roundId()));
    }
}
