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
class ContactDatabaseTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        String url=System.getenv("TEST_DB_URL");
        if(url==null||!url.matches("jdbc:postgresql://[^/]+/[A-Za-z0-9_]+_test(?:\\?.*)?"))throw new IllegalStateException("Dedicated _test database required");
        p.add("spring.datasource.url",()->url);p.add("spring.datasource.username",()->System.getenv("TEST_DB_USERNAME"));p.add("spring.datasource.password",()->System.getenv("TEST_DB_PASSWORD"));
    }
    @Autowired ContactService contacts; @Autowired MatchService matches; @Autowired ProfileService profiles;
    @Autowired RequestDraftService drafts; @Autowired RequestPublicationService publication;
    @Autowired JdbcTemplate db; @Autowired ObjectMapper json; @Autowired Secrets secrets;
    @MockitoBean MailGateway mail;
    private final List<UUID> actors=new ArrayList<>();
    private record User(UUID id,String session) {}
    private record Setup(User owner,User tutor,User operator,UUID request,UUID submission,long teacherVersion,MatchView match) {}
    private String key(){return UUID.randomUUID().toString();}
    private User user(String role) {
        UUID id=UUID.randomUUID();String name="test_contact_"+id.toString().replace("-","").substring(0,18),session=secrets.token();
        db.update("INSERT INTO accounts(id,account,account_key,email,email_key,password_hash,adult_confirmed_at,email_verified,business_identity,registration_flow_id,registration_context_hash) VALUES(?,?,?,?,?,'synthetic-no-login',now(),true,?,?,?)",id,name,name,name+"@example.invalid",name+"@example.invalid",role,UUID.randomUUID(),secrets.digest(key()));
        db.update("INSERT INTO profiles(account_id,display_name) VALUES(?,'合成展示名')",id);
        db.update("INSERT INTO auth_sessions(token_hash,account_id,expires_at) VALUES(?,?,now()+interval '1 hour')",secrets.digest(session),id);
        actors.add(id);return new User(id,session);
    }
    private Setup setup(boolean invite,boolean accept) throws Exception {
        User owner=user("REQUESTER"),tutor=user("TUTOR"),operator=user("TUTOR");db.update("UPDATE accounts SET operator_granted=true WHERE id=?",operator.id());
        var p=profiles.saveTeacher(tutor.session(),new TeacherBody(0L,"合成老师",List.of("DRAWING"),List.of("ONLINE"),List.of(),"合成介绍",null,null,""));
        long teacherVersion=profiles.visibility(tutor.session(),new VersionBody(p.version()),true,key()).version();
        var d=drafts.create(owner.session(),key());var saved=drafts.save(owner.session(),d.id(),new DraftPatch(d.version(),(ObjectNode)json.readTree("""
            {"title":"联系方式闭环需求","categoryCode":"DRAWING","audienceStageCode":"ADULT","areaCode":"PS_PINGSHAN","goal":"合成目标",
             "budgetMin":"100","budgetMax":"200","durationHours":"1","frequency":{"mode":"NEGOTIABLE"},"availableSlots":[{"weekday":1,"startMinute":600,"endMinute":660}]}
            """)));
        var submitted=publication.submit(owner.session(),d.id(),new SubmitRequest(saved.version(),"SELF_ADULT","publisher-v1"),key());
        publication.decide(operator.session(),submitted.submissionId(),new ReviewRequest(0L,"APPROVE",null),key());
        var m=matches.start(invite?owner.session():tutor.session(),new StartMatch(d.id(),submitted.submissionId(),invite?"INVITATION":"APPLICATION",invite?tutor.id():null,teacherVersion),key()).match();
        if(accept)m=matches.respond(invite?tutor.session():owner.session(),m.id(),new RespondMatch(m.roundId(),m.version()),key(),"ACCEPT");
        contacts.save(owner.session(),new SaveContacts(0L,"13800000001","owner_contact"));contacts.save(tutor.session(),new SaveContacts(0L,"13800000002","tutor_contact"));
        return new Setup(owner,tutor,operator,d.id(),submitted.submissionId(),teacherVersion,m);
    }
    private Map<String,Object> grant(User u,MatchView m){return contacts.grant(u.session(),m.id());}
    private AgreeContacts body(User u,MatchView m,List<String> fields){return new AgreeContacts(m.roundId(),((Number)grant(u,m).get("version")).longValue(),fields,contacts.get(u.session()).fieldVersions(),true);}
    private Map<String,Object> agree(User u,MatchView m,String... fields){return contacts.agree(u.session(),m.id(),body(u,m,List.of(fields)),key());}
    private Map<String,Object> withdraw(User u,MatchView m){return contacts.withdraw(u.session(),m.id(),new WithdrawContacts(m.roundId(),((Number)grant(u,m).get("version")).longValue()),key());}
    private void hidden(Map<String,Object> g){assertEquals(false,g.get("canViewContacts"));assertFalse(g.containsKey("mySharedContacts"));assertFalse(g.containsKey("otherSharedContacts"));}
    @AfterEach void cleanup(){
        for(UUID id:actors)db.update("DELETE FROM match_relationships WHERE requester_id=? OR tutor_id=?",id,id);
        for(UUID id:actors)db.update("DELETE FROM service_requests WHERE owner_id=?",id);
        for(UUID id:actors)db.update("DELETE FROM accounts WHERE id=?",id);
    }
    @Test void contactsAreOptionalPrivateVersionedAndNotAProfileChange() throws Exception {
        User u=user("TUTOR");assertEquals(new Contacts(0,null,null,new ContactFields(0L,0L)),contacts.get(u.session()));
        contacts.save(u.session(),new SaveContacts(0L,"13800000000","test_contact"));var c=contacts.get(u.session());
        assertEquals(1,c.version());assertEquals(0,profiles.get(u.session()).version());
        assertEquals("VERSION_CONFLICT",assertThrows(ApiError.class,()->contacts.save(u.session(),new SaveContacts(0L,null,null))).code);
        contacts.save(u.session(),new SaveContacts(c.version(),null,c.wechat()));var removed=contacts.get(u.session());assertNull(removed.phone());assertEquals(2,removed.fieldVersions().phone());assertEquals(1,removed.fieldVersions().wechat());
        contacts.save(u.session(),new SaveContacts(removed.version(),null,null));assertNull(contacts.get(u.session()).wechat());
        assertEquals(401,assertThrows(ApiError.class,()->contacts.get(null)).status);
    }
    @Test void onlyMutualParticipantsCanConsentAndPreviewRequiresCurrentNonemptyFields() throws Exception {
        var s=setup(false,false);hidden(grant(s.owner(),s.match()));
        assertEquals("NOT_MUTUAL",assertThrows(ApiError.class,()->agree(s.owner(),s.match(),"PHONE")).code);
        var outsider=user("TUTOR");assertEquals(404,assertThrows(ApiError.class,()->grant(outsider,s.match())).status);
        assertEquals(404,assertThrows(ApiError.class,()->contacts.agree(outsider.session(),s.match().id(),new AgreeContacts(s.match().roundId(),0L,List.of("PHONE"),new ContactFields(1L,1L),true),key())).status);
        var m=matches.respond(s.owner().session(),s.match().id(),new RespondMatch(s.match().roundId(),s.match().version()),key(),"ACCEPT");
        var old=body(s.owner(),m,List.of("PHONE"));contacts.save(s.owner().session(),new SaveContacts(1L,"13800000009","owner_contact"));
        assertEquals("CONTACT_FIELD_CHANGED",assertThrows(ApiError.class,()->contacts.agree(s.owner().session(),m.id(),old,key())).code);
        contacts.save(s.owner().session(),new SaveContacts(2L,null,"owner_contact"));assertEquals("CONTACT_FIELD_EMPTY",assertThrows(ApiError.class,()->agree(s.owner(),m,"PHONE")).code);
        assertThrows(ApiError.class,()->agree(s.owner(),m,"EMAIL"));
    }
    @Test void bothPathsShareOnlySelectedFieldsAfterBothConsent() throws Exception {
        for(boolean invite:List.of(false,true)){
            var s=setup(invite,true);hidden(agree(s.owner(),s.match(),"PHONE"));var g=agree(s.tutor(),s.match(),"WECHAT");
            assertEquals(Map.of("wechat","tutor_contact"),g.get("mySharedContacts"));assertEquals(Map.of("phone","13800000001"),g.get("otherSharedContacts"));
            assertEquals(Map.of("wechat","tutor_contact"),grant(s.owner(),s.match()).get("otherSharedContacts"));
            String snapshots=json.writeValueAsString(matches.detail(s.owner().session(),s.match().id()));
            for(String f:List.of("phone","wechat","email"))assertFalse(snapshots.contains('"'+f+'"'));
            assertFalse(json.writeValueAsString(profiles.publicTeacher(s.owner().session(),s.tutor().id())).contains("tutor_contact"));
            var both=agree(s.owner(),s.match(),"PHONE","WECHAT");assertEquals(Map.of("phone","13800000001","wechat","owner_contact"),both.get("mySharedContacts"));
            assertEquals(Map.of("wechat","tutor_contact"),both.get("otherSharedContacts"));
        }
    }
    @Test void withdrawingPreservesOtherIndependentConsentAndNeverReplaysPrivateValues() throws Exception {
        var s=setup(false,true);var body=body(s.owner(),s.match(),List.of("PHONE"));String receipt=key();
        contacts.agree(s.owner().session(),s.match().id(),body,receipt);agree(s.tutor(),s.match(),"WECHAT");
        hidden(withdraw(s.owner(),s.match()));var other=grant(s.tutor(),s.match());hidden(other);assertEquals(true,other.get("myConsent"));assertEquals(false,other.get("otherConsent"));
        hidden(contacts.agree(s.owner().session(),s.match().id(),body,receipt));
        assertFalse(db.queryForObject("SELECT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_name='contact_action_receipts' AND column_name='result')",Boolean.class));
        assertEquals(409,assertThrows(ApiError.class,()->contacts.agree(s.owner().session(),s.match().id(),new AgreeContacts(body.roundId(),body.expectedGrantVersion(),List.of("WECHAT"),body.expectedContactFieldVersions(),true),receipt)).status);
        assertEquals(true,agree(s.owner(),s.match(),"PHONE").get("canViewContacts"));
    }
    @Test void changingSelectedFieldsInvalidatesBothEvenBeforeOtherConsent() throws Exception {
        var s=setup(true,true);agree(s.owner(),s.match(),"PHONE");var stale=body(s.tutor(),s.match(),List.of("WECHAT"));
        var original=body(s.owner(),s.match(),List.of("PHONE"));String receipt=key();contacts.agree(s.owner().session(),s.match().id(),original,receipt);
        var result=contacts.save(s.owner().session(),new SaveContacts(1L,"13800000003","owner_contact"));assertEquals(1,result.get("affectedGrantCount"));
        var g=grant(s.owner(),s.match());hidden(g);assertEquals(true,g.get("requiresReconfirmation"));assertEquals(true,g.get("otherRequiresReconfirmation"));assertEquals(false,g.get("otherConsent"));
        hidden(contacts.agree(s.owner().session(),s.match().id(),original,receipt));
        assertEquals(409,assertThrows(ApiError.class,()->contacts.agree(s.tutor().session(),s.match().id(),stale,key())).status);
        hidden(agree(s.owner(),s.match(),"PHONE"));assertEquals(Map.of("phone","13800000003"),agree(s.tutor(),s.match(),"WECHAT").get("otherSharedContacts"));
    }
    @Test void deletingSharedFieldAndChangingItBackStillRequiresBothReconfirmation() throws Exception {
        var s=setup(false,true);agree(s.owner(),s.match(),"PHONE");agree(s.tutor(),s.match(),"WECHAT");
        var old=body(s.owner(),s.match(),List.of("PHONE"));contacts.save(s.owner().session(),new SaveContacts(1L,null,"owner_contact"));
        hidden(grant(s.tutor(),s.match()));contacts.save(s.owner().session(),new SaveContacts(2L,"13800000001","owner_contact"));
        hidden(grant(s.owner(),s.match()));assertEquals(409,assertThrows(ApiError.class,()->contacts.agree(s.owner().session(),s.match().id(),old,key())).status);
        hidden(agree(s.tutor(),s.match(),"WECHAT"));assertEquals(true,agree(s.owner(),s.match(),"PHONE").get("canViewContacts"));
    }
    @Test void unselectedEditsAndOtherRelationshipsStayIndependent() throws Exception {
        var s=setup(false,true);var another=setup(true,true);
        // Same owner/tutor, separate request and relationship.
        var d=drafts.create(s.owner().session(),key());var original=drafts.get(another.owner().session(),another.request());
        var saved=drafts.save(s.owner().session(),d.id(),new DraftPatch(0L,(ObjectNode)json.valueToTree(original.fields())));
        var submission=publication.submit(s.owner().session(),d.id(),new SubmitRequest(saved.version(),"SELF_ADULT","publisher-v1"),key());publication.decide(s.operator().session(),submission.submissionId(),new ReviewRequest(0L,"APPROVE",null),key());
        var m=matches.start(s.owner().session(),new StartMatch(d.id(),submission.submissionId(),"INVITATION",s.tutor().id(),s.teacherVersion()),key()).match();
        m=matches.respond(s.tutor().session(),m.id(),new RespondMatch(m.roundId(),m.version()),key(),"ACCEPT");
        agree(s.owner(),s.match(),"PHONE");agree(s.tutor(),s.match(),"WECHAT");agree(s.owner(),m,"WECHAT");agree(s.tutor(),m,"PHONE");
        contacts.save(s.owner().session(),new SaveContacts(1L,"13800000008","owner_contact"));
        hidden(grant(s.owner(),s.match()));assertEquals(true,grant(s.owner(),m).get("canViewContacts"));
        withdraw(s.owner(),m);assertEquals(true,grant(s.tutor(),m).get("myConsent"));assertEquals(true,grant(s.owner(),s.match()).get("requiresReconfirmation"));
    }
    @Test void existingMutualConsentSurvivesExpirationUnpublishingAndRevision() throws Exception {
        var s=setup(true,true);db.update("UPDATE request_submissions SET submitted_at=now()-interval '16 days',expires_at=now() WHERE id=?",s.submission());
        profiles.visibility(s.tutor().session(),new VersionBody(s.teacherVersion()),false,key());
        var d=drafts.get(s.owner().session(),s.request());publication.revise(s.owner().session(),s.request(),new VersionBody(d.version()),key());
        hidden(agree(s.owner(),s.match(),"PHONE"));assertEquals(true,agree(s.tutor(),s.match(),"WECHAT").get("canViewContacts"));hidden(withdraw(s.tutor(),s.match()));
    }
    @Test void freshRoundHasNoConsentAndRejectsOldRound() throws Exception {
        var s=setup(true,false);matches.respond(s.tutor().session(),s.match().id(),new RespondMatch(s.match().roundId(),s.match().version()),key(),"REJECT");
        var next=matches.start(s.tutor().session(),new StartMatch(s.request(),s.submission(),"APPLICATION",null,s.teacherVersion()),key()).match();
        next=matches.respond(s.owner().session(),next.id(),new RespondMatch(next.roundId(),next.version()),key(),"ACCEPT");hidden(grant(s.owner(),next));assertEquals(0L,grant(s.owner(),next).get("version"));
        var stale=new AgreeContacts(s.match().roundId(),0L,List.of("PHONE"),new ContactFields(1L,1L),true);
        UUID id=next.id();assertEquals(409,assertThrows(ApiError.class,()->contacts.agree(s.owner().session(),id,stale,key())).status);
    }
    private int attempt(Supplier<?> action){try{action.get();return 200;}catch(ApiError e){return e.status;}}
    private List<Integer> race(Supplier<?> one,Supplier<?> two) throws Exception {
        var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try{var a=pool.submit(()->{gate.await();return attempt(one);});var b=pool.submit(()->{gate.await();return attempt(two);});gate.countDown();return List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));}finally{pool.shutdownNow();}
    }
    @Test void simultaneousConsentHasOneWinnerThenExplicitFreshConfirmation() throws Exception {
        var s=setup(false,true);var a=body(s.owner(),s.match(),List.of("PHONE"));var b=body(s.tutor(),s.match(),List.of("WECHAT"));
        var results=race(()->contacts.agree(s.owner().session(),s.match().id(),a,key()),()->contacts.agree(s.tutor().session(),s.match().id(),b,key()));
        assertEquals(List.of(200,409),results.stream().sorted().toList());hidden(grant(s.owner(),s.match()));
        if(!(boolean)grant(s.owner(),s.match()).get("myConsent"))agree(s.owner(),s.match(),"PHONE");else agree(s.tutor(),s.match(),"WECHAT");
        assertEquals(true,grant(s.owner(),s.match()).get("canViewContacts"));
    }
    @Test void consentWithdrawalRaceAndLostReceiptNeverUndoLaterWithdrawal() throws Exception {
        var s=setup(false,true);agree(s.owner(),s.match(),"PHONE");var b=body(s.tutor(),s.match(),List.of("WECHAT"));
        var withdrawal=new WithdrawContacts(s.match().roundId(),b.expectedGrantVersion());String receipt=key();
        var results=race(()->contacts.agree(s.tutor().session(),s.match().id(),b,key()),()->contacts.withdraw(s.owner().session(),s.match().id(),withdrawal,receipt));assertEquals(List.of(200,409),results.stream().sorted().toList());
        withdraw(s.owner(),s.match());hidden(grant(s.tutor(),s.match()));
        if(results.get(1)==200)hidden(contacts.withdraw(s.owner().session(),s.match().id(),withdrawal,receipt));
    }
    @Test void simultaneousContactEditsAndConsentStayConsistentWithoutDeadlocks() throws Exception {
        var s=setup(false,true);agree(s.owner(),s.match(),"PHONE");agree(s.tutor(),s.match(),"WECHAT");
        var results=race(()->contacts.save(s.owner().session(),new SaveContacts(1L,"13800000009","owner_contact")),()->contacts.save(s.tutor().session(),new SaveContacts(1L,"13800000002","changed_tutor")));
        assertEquals(List.of(200,200),results);hidden(grant(s.owner(),s.match()));
        var b=body(s.tutor(),s.match(),List.of("WECHAT"));
        race(()->contacts.agree(s.tutor().session(),s.match().id(),b,key()),()->contacts.save(s.owner().session(),new SaveContacts(2L,"13800000007","owner_contact")));
        hidden(grant(s.owner(),s.match()));assertEquals(true,grant(s.owner(),s.match()).get("requiresReconfirmation"));
    }
}
