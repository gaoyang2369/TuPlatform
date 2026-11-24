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
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

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
    @Autowired PlatformTransactionManager transactions;
    @Autowired NotificationService notifications;
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
        var saved=profiles.saveTeacher(tutor.session(),ResumeFixtures.teacher(p.version(),"当时老师",List.of("DRAWING"),List.of("ONLINE"),List.of(),"合成介绍",null,null,""));
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
        var started=db.queryForMap("SELECT user_id,content,target_url FROM notifications WHERE event_id=? AND event_type='match_started'",m.roundId());
        assertEquals(tutor.id(),started.get("user_id"));
        assertTrue(started.get("content").toString().contains("合成展示名"));
        assertFalse(started.get("content").toString().contains("想了解周末课程"));
        assertTrue(started.get("target_url").toString().endsWith("?view=matches&match="+m.id()));
        assertEquals(owner.id(),db.queryForObject("SELECT user_id FROM notifications WHERE event_id=? AND event_type='match_accepted'",UUID.class,m.roundId()));
        assertFalse(json.writeValueAsString(m).contains("@example.invalid"));
    }
    @Test void notificationRollbackRetriesAndReplayStaySeparateFromTheMatch() throws Exception {
        var setup=setup();
        var match=start(setup,false);
        assertEquals(setup.owner().id(),db.queryForObject("SELECT user_id FROM notifications WHERE event_id=?",UUID.class,match.roundId()));
        var content=db.queryForObject("SELECT content FROM notifications WHERE event_id=?",String.class,match.roundId());
        assertTrue(content.contains("合成撮合需求")); assertTrue(content.contains("当时老师"));
        UUID transientRound=UUID.randomUUID();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            notifications.mutual(setup.tutor().id(), transientRound, match.id());
            tx.setRollbackOnly();
        });
        assertEquals(0,db.queryForObject("SELECT count(*) FROM notifications WHERE event_id=?",Integer.class,transientRound));
        var transport=mock(NotificationMail.class);
        when(transport.deliver(any(UUID.class),anyString(),anyString(),anyString(),anyString())).thenThrow(new MailSendException("synthetic outage"));
        var worker=new NotificationDispatcher(db,transport,transactions);
        for(int i=0;i<3;i++) {
            db.update("UPDATE notifications SET next_attempt_at=now() WHERE event_id=?",match.roundId());
            worker.dispatch();
        }
        assertEquals("failed",db.queryForObject("SELECT sent_status FROM notifications WHERE event_id=?",String.class,match.roundId()));
        assertEquals("WAITING",matches.detail(setup.owner().session(),match.id()).state());
        act(setup.owner(),match,"ACCEPT");
        notifications.mutual(setup.tutor().id(),match.roundId(),match.id());
        var localMail=new NotificationMail(mock(JavaMailSender.class),json,"test@example.invalid",false,"",true,"","");
        new NotificationDispatcher(db,localMail,transactions).dispatch();
        var delivered=db.queryForMap("SELECT user_id,sent_status,attempts,is_read FROM notifications WHERE event_id=? AND event_type='match_accepted'",match.roundId());
        assertEquals(setup.tutor().id(),delivered.get("user_id")); assertEquals("logged",delivered.get("sent_status"));
        assertEquals(1,delivered.get("attempts")); assertEquals(false,delivered.get("is_read"));
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
    @Test void sendAndAcceptRecordConsentAtomicallyWithoutExtraSharingRound() throws Exception {
        var owner=user("REQUESTER");var tutor=user("TUTOR");long version=publish(tutor);
        contacts.save(owner.session(),new SaveContacts(0L,"13800000000","synthetic_owner"));
        contacts.save(tutor.session(),new SaveContacts(0L,null,"synthetic_tutor"));
        var body=new StartMatch(null,null,"DIRECT_INVITATION",tutor.id(),version,"",new ContactExchange(true,1L));String startKey=key();
        var m=matches.start(owner.session(),body,startKey).match();var waiting=contacts.grant(owner.session(),m.id());
        assertEquals(true,waiting.get("myConsent"));assertEquals(false,waiting.get("canViewContacts"));assertFalse(waiting.containsKey("otherSharedContacts"));
        var accept=new RespondMatch(m.roundId(),m.version(),new ContactExchange(true,1L));String acceptKey=key();
        matches.respond(tutor.session(),m.id(),accept,acceptKey,"ACCEPT");var mutual=contacts.grant(owner.session(),m.id());
        assertEquals(true,mutual.get("canViewContacts"));assertEquals(Map.of("wechat","synthetic_tutor"),mutual.get("otherSharedContacts"));
        assertEquals(Map.of("phone","13800000000","wechat","synthetic_owner"),contacts.grant(tutor.session(),m.id()).get("otherSharedContacts"));
        contacts.withdraw(tutor.session(),m.id(),new WithdrawContacts(m.roundId(),((Number)mutual.get("version")).longValue()),key());
        matches.respond(tutor.session(),m.id(),accept,acceptKey,"ACCEPT");matches.start(owner.session(),body,startKey);
        assertEquals(false,contacts.grant(owner.session(),m.id()).get("canViewContacts")); // Receipt replay cannot restore sharing.
        String receipts=db.queryForObject("SELECT string_agg(result::text,',') FROM match_action_receipts WHERE match_id=?",String.class,m.id());
        assertFalse(receipts.contains("synthetic_owner"));assertFalse(receipts.contains("13800000000"));
    }
    @Test void changedContactsRollBackSendAndAcceptAndDoNotLeakIntoNewConsent() {
        var owner=user("REQUESTER");var tutor=user("TUTOR");long version=publish(tutor);
        contacts.save(owner.session(),new SaveContacts(0L,null,"original_owner"));
        var stale=new StartMatch(null,null,"DIRECT_INVITATION",tutor.id(),version,"",new ContactExchange(true,0L));
        assertEquals("CONTACT_FIELD_CHANGED",assertThrows(ApiError.class,()->matches.start(owner.session(),stale,key())).code);
        assertEquals(0,db.queryForObject("SELECT count(*) FROM match_relationships WHERE requester_id=?",Integer.class,owner.id()));
        var m=matches.start(owner.session(),new StartMatch(null,null,"DIRECT_INVITATION",tutor.id(),version,"",new ContactExchange(true,1L)),key()).match();
        contacts.save(tutor.session(),new SaveContacts(0L,null,"new_tutor"));
        assertEquals("CONTACT_FIELD_CHANGED",assertThrows(ApiError.class,()->matches.respond(tutor.session(),m.id(),new RespondMatch(m.roundId(),m.version(),new ContactExchange(true,0L)),key(),"ACCEPT")).code);
        assertEquals("WAITING",matches.detail(owner.session(),m.id()).state());assertEquals(false,contacts.grant(owner.session(),m.id()).get("otherConsent"));
        contacts.save(owner.session(),new SaveContacts(1L,null,"changed_owner"));
        matches.respond(tutor.session(),m.id(),new RespondMatch(m.roundId(),m.version(),new ContactExchange(true,1L)),key(),"ACCEPT");
        assertEquals(false,contacts.grant(tutor.session(),m.id()).get("canViewContacts"));
    }
    @Test void emptyContactsAndLegacyIntentionsNeverAuthorizeFutureValues() {
        var owner=user("REQUESTER");var tutor=user("TUTOR");long version=publish(tutor);
        var m=matches.start(owner.session(),new StartMatch(null,null,"DIRECT_INVITATION",tutor.id(),version,"",new ContactExchange(true,0L)),key()).match();
        contacts.save(owner.session(),new SaveContacts(0L,null,"later_owner"));contacts.save(tutor.session(),new SaveContacts(0L,null,"new_tutor"));
        matches.respond(tutor.session(),m.id(),new RespondMatch(m.roundId(),m.version(),new ContactExchange(true,1L)),key(),"ACCEPT");
        assertEquals(false,contacts.grant(tutor.session(),m.id()).get("canViewContacts"));
        var legacyOwner=user("REQUESTER");contacts.save(legacyOwner.session(),new SaveContacts(0L,null,"legacy_owner"));
        var legacy=matches.start(legacyOwner.session(),direct(tutor,version,""),key()).match();
        matches.respond(tutor.session(),legacy.id(),new RespondMatch(legacy.roundId(),legacy.version(),new ContactExchange(true,1L)),key(),"ACCEPT");
        assertEquals(false,contacts.grant(tutor.session(),legacy.id()).get("otherConsent"));
        matches.start(legacyOwner.session(),new StartMatch(null,null,"DIRECT_INVITATION",tutor.id(),version,"",new ContactExchange(true,1L)),key());
        assertEquals(false,contacts.grant(tutor.session(),legacy.id()).get("otherConsent")); // EXISTING is navigation only.
    }
    @Test void privateAddressFollowsTheCurrentRoundAndNeverEntersSnapshotsOrReceipts() throws Exception {
        var s=setup();
        String address="合成私密地点3栋";
        db.update("UPDATE request_submissions SET fields=fields || jsonb_build_object('exact_address',?::text) WHERE id=?",
            address,s.submission());
        contacts.save(s.owner().session(),new SaveContacts(0L,null,"owner_address_test"));
        contacts.save(s.tutor().session(),new SaveContacts(0L,null,"tutor_address_test"));
        var send=new StartMatch(s.request(),s.submission(),"APPLICATION",null,s.teacherVersion(),null,new ContactExchange(true,1L));
        String sendKey=key();
        var m=matches.start(s.tutor().session(),send,sendKey).match();
        db.update("UPDATE match_rounds SET submission_snapshot=submission_snapshot || jsonb_build_object('locationText',?::text) WHERE id=?",
            address,m.roundId());
        assertNull(matches.privateDetail(s.tutor().session(),m.id()).exact_address());
        assertFalse(json.writeValueAsString(matches.list(s.tutor().session(),null,null,null)).contains(address));
        var accept=new RespondMatch(m.roundId(),m.version(),new ContactExchange(true,1L));
        matches.respond(s.owner().session(),m.id(),accept,key(),"ACCEPT");
        assertEquals(address,matches.privateDetail(s.tutor().session(),m.id()).exact_address());
        assertNull(matches.privateDetail(s.owner().session(),m.id()).exact_address());
        assertFalse(json.writeValueAsString(matches.start(s.tutor().session(),send,sendKey)).contains(address));
        assertFalse(json.writeValueAsString(contacts.grant(s.tutor().session(),m.id())).contains(address));
        contacts.save(s.owner().session(),new SaveContacts(1L,null,"changed_address_test"));
        assertNull(matches.privateDetail(s.tutor().session(),m.id()).exact_address());
        assertEquals(404,assertThrows(ApiError.class,()->matches.privateDetail(user("TUTOR").session(),m.id())).status);
    }
    @Test void applicationAndRequestInvitationAlsoIntegrateContactConsent() throws Exception {
        for(boolean invite:List.of(false,true)){
            var s=setup();contacts.save(s.owner().session(),new SaveContacts(0L,null,"owner_contact"));contacts.save(s.tutor().session(),new SaveContacts(0L,null,"tutor_contact"));
            var base=body(s,invite);var body=new StartMatch(base.requestId(),base.expectedSubmissionId(),base.kind(),base.tutorUserId(),base.expectedTeacherProfileVersion(),null,new ContactExchange(true,1L));
            var m=matches.start(invite?s.owner().session():s.tutor().session(),body,key()).match();
            matches.respond(invite?s.tutor().session():s.owner().session(),m.id(),new RespondMatch(m.roundId(),m.version(),new ContactExchange(true,1L)),key(),"ACCEPT");
            assertEquals(true,contacts.grant(s.owner().session(),m.id()).get("canViewContacts"));
        }
    }
    @Autowired MatchLifecycle lifecycle;
    @Autowired MatchReviewService reviews;

    private MatchView finish(User owner, User tutor, MatchView exchanged) {
        var working = act(tutor,act(owner,exchanged,"request-cooperation"),"confirm-cooperation");
        return act(owner,act(tutor,working,"request-completion"),"confirm-completion");
    }

    private MatchReviewService.Submission feedback(MatchView match, boolean parent) {
        var scores = parent ? Map.of("守时履约",5,"讲解清晰度",4,"备课用心度",4,"孩子接受度",5)
            : Map.of("需求描述真实",4,"沟通尊重顺畅",3,"课时费结算及时",4);
        return new MatchReviewService.Submission(match.roundId(),json.valueToTree(scores),
            List.of(parent ? "守时靠谱" : "沟通顺畅"),parent ? "合成家长反馈" : "合成老师反馈");
    }

    @Test void blindReviewsRevealTogetherAndAllowOnlyRecipientToReplyOnce() {
        var owner=user("REQUESTER"); var tutor=user("TUTOR"); var outsider=user("REQUESTER");
        var exchanged=exchanged(owner,tutor);
        var a=feedback(exchanged,true); var b=feedback(exchanged,false);
        assertEquals(409,assertThrows(ApiError.class,()->reviews.submit(owner.session(),exchanged.id(),a,key())).status);
        var m=finish(owner,tutor,exchanged);
        assertEquals(404,assertThrows(ApiError.class,()->reviews.submit(outsider.session(),m.id(),a,key())).status);
        assertEquals(404,assertThrows(ApiError.class,()->reviews.list(outsider.session(),m.id())).status);
        assertEquals(422,assertThrows(ApiError.class,()->reviews.submit(owner.session(),m.id(),b,key())).status);
        var badTags=new MatchReviewService.Submission(m.roundId(),a.subScores(),List.of("守时靠谱","守时靠谱"),"");
        assertEquals(422,assertThrows(ApiError.class,()->reviews.submit(owner.session(),m.id(),badTags,key())).status);
        var keyA=key();
        reviews.submit(owner.session(),m.id(),a,keyA);
        reviews.submit(owner.session(),m.id(),a,keyA);
        assertEquals(409,assertThrows(ApiError.class,()->reviews.submit(owner.session(),m.id(),a,key())).status);
        var first=reviews.list(owner.session(),m.id()).reviews().getFirst();
        assertFalse(first.published()); assertEquals(5,first.overallScore()); assertEquals(m.reviewDeadlineAt(),first.revealAt());
        assertTrue(reviews.list(tutor.session(),m.id()).reviews().isEmpty());
        assertFalse(matches.detail(tutor.session(),m.id()).ownReviewSubmitted());
        assertFalse(matches.detail(tutor.session(),m.id()).reviewsPublished());
        var reply=new MatchReviewService.Reply("谢谢你的反馈");
        assertEquals(404,assertThrows(ApiError.class,()->reviews.reply(tutor.session(),first.id(),reply,key())).status);
        reviews.submit(tutor.session(),m.id(),b,key());
        var visible=reviews.list(owner.session(),m.id()).reviews();
        assertEquals(2,visible.size()); assertTrue(visible.stream().allMatch(MatchReviewService.Entry::published));
        assertEquals(2,reviews.list(tutor.session(),m.id()).reviews().size());
        assertEquals(1,db.queryForObject("SELECT count(*) FROM notifications WHERE event_id=? AND event_type='review_submitted' AND user_id=?",Integer.class,m.roundId(),tutor.id()));
        assertEquals(2,db.queryForObject("SELECT count(*) FROM notifications WHERE event_id=? AND event_type='reviews_revealed'",Integer.class,m.roundId()));
        for(var entry:visible) {
            var recipient=entry.mine()?tutor:owner;
            assertEquals(404,assertThrows(ApiError.class,()->reviews.reply(outsider.session(),entry.id(),reply,key())).status);
            assertEquals(404,assertThrows(ApiError.class,()->reviews.reply((entry.mine()?owner:tutor).session(),entry.id(),reply,key())).status);
            assertEquals(422,assertThrows(ApiError.class,()->reviews.reply(recipient.session(),entry.id(),new MatchReviewService.Reply("字".repeat(201)),key())).status);
            String receipt=key();
            reviews.reply(recipient.session(),entry.id(),reply,receipt);
            reviews.reply(recipient.session(),entry.id(),reply,receipt);
            assertEquals(409,assertThrows(ApiError.class,()->reviews.reply(recipient.session(),entry.id(),reply,key())).status);
        }
        assertTrue(reviews.list(tutor.session(),m.id()).reviews().stream().allMatch(e->reply.content().equals(e.replyContent()) && e.repliedAt()!=null));
    }

    @Test void expiredSingleReviewIsRevealedAndSurvivesANewRound() {
        var owner=user("REQUESTER"); var tutor=user("TUTOR");
        var m=finish(owner,tutor,exchanged(owner,tutor));
        reviews.submit(tutor.session(),m.id(),feedback(m,false),key());
        db.update("UPDATE match_rounds SET completed_at=now()-interval '15 days',review_deadline_at=now()-interval '1 day' WHERE id=?",m.roundId());
        db.update("UPDATE reviews SET reveal_at=(SELECT review_deadline_at FROM match_rounds WHERE id=?) WHERE match_id=?",m.roundId(),m.roundId());
        assertEquals(409,assertThrows(ApiError.class,()->reviews.submit(owner.session(),m.id(),feedback(m,true),key())).status);
        var visible=reviews.list(owner.session(),m.id());
        assertFalse(visible.canSubmit()); assertEquals(1,visible.reviews().size()); assertTrue(visible.reviews().getFirst().published());
        var next=matches.start(owner.session(),direct(tutor,profiles.get(tutor.session()).version(),""),key()).match();
        assertNotEquals(next.roundId(),m.roundId()); assertTrue(next.hasPastReviews()); assertFalse(next.ownReviewSubmitted());
        assertEquals(visible.reviews(),reviews.list(owner.session(),next.id()).reviews());
        reviews.reply(owner.session(),visible.reviews().getFirst().id(),new MatchReviewService.Reply("谢谢"),key());
    }

    @Test void scheduledRevealUsesTheCopiedDeadlineAndIsSafeToRepeat() {
        var owner=user("REQUESTER"); var tutor=user("TUTOR");
        var m=finish(owner,tutor,exchanged(owner,tutor));
        reviews.submit(owner.session(),m.id(),feedback(m,true),key());
        reviews.publishExpiredReviews();
        assertFalse(db.queryForObject("SELECT is_published FROM reviews WHERE match_id=?",Boolean.class,m.roundId()));
        db.update("UPDATE reviews SET reveal_at=now()-interval '1 second' WHERE match_id=?",m.roundId());
        reviews.publishExpiredReviews();
        reviews.publishExpiredReviews();
        assertTrue(db.queryForObject("SELECT is_published FROM reviews WHERE match_id=?",Boolean.class,m.roundId()));
    }

    @Test void simultaneousSubmissionsPublishBothAndEnqueueOnce() throws Exception {
        var owner=user("REQUESTER"); var tutor=user("TUTOR");
        var m=finish(owner,tutor,exchanged(owner,tutor));
        race(()->{ reviews.submit(owner.session(),m.id(),feedback(m,true),key()); return true; },
             ()->{ reviews.submit(tutor.session(),m.id(),feedback(m,false),key()); return true; });
        var entries=reviews.list(owner.session(),m.id()).reviews();
        assertEquals(2,entries.size()); assertTrue(entries.stream().allMatch(MatchReviewService.Entry::published));
        assertEquals(3,db.queryForObject("SELECT count(*) FROM notifications WHERE event_id=? AND event_type IN ('review_submitted','reviews_revealed')",Integer.class,m.roundId()));
    }

    private MatchView exchanged(User owner, User tutor) {
        contacts.save(owner.session(),new SaveContacts(0L,null,"synthetic_owner"));
        contacts.save(tutor.session(),new SaveContacts(0L,null,"synthetic_tutor"));
        var m=matches.start(owner.session(),new StartMatch(null,null,"DIRECT_INVITATION",tutor.id(),publish(tutor),
            "",new ContactExchange(true,1L)),key()).match();
        return matches.respond(tutor.session(),m.id(),new RespondMatch(m.roundId(),m.version(),new ContactExchange(true,1L)),key(),"ACCEPT");
    }

    @Test void onlyExchangedParticipantsCanConfirmAndUnlockReviews() {
        var owner=user("REQUESTER"); var tutor=user("TUTOR");
        var m=exchanged(owner,tutor);
        assertTrue(m.contactsExchanged());
        assertEquals(404,assertThrows(ApiError.class,()->act(user("TUTOR"),m,"request-cooperation")).status);
        String requestKey=key();
        var pending=matches.respond(owner.session(),m.id(),response(m),requestKey,"request-cooperation");
        assertEquals(pending,matches.respond(owner.session(),m.id(),response(m),requestKey,"request-cooperation"));
        assertEquals(owner.id(),pending.cooperationRequestedBy());
        assertEquals(List.of(tutor.id()),db.queryForList(
            "SELECT user_id FROM notifications WHERE target_url LIKE ? AND event_type='cooperation_requested'",
            UUID.class,"%match="+m.id()));
        assertThrows(ApiError.class,()->act(owner,pending,"confirm-cooperation"));
        // Both acted on the same exchanged version. The opposite request confirms.
        var working=act(tutor,m,"request-cooperation");
        assertEquals("in_cooperation",working.state());
        assertEquals(true,contacts.grant(tutor.session(),m.id()).get("canViewContacts"));
        var finishing=act(tutor,working,"request-completion");
        assertEquals(List.of(owner.id()),db.queryForList(
            "SELECT user_id FROM notifications WHERE target_url LIKE ? AND event_type='completion_requested'",
            UUID.class,"%match="+m.id()));
        assertThrows(ApiError.class,()->act(tutor,finishing,"confirm-completion"));
        var disputed=act(owner,finishing,"continue-cooperation");
        assertNull(disputed.completionRequestedBy());
        assertEquals("in_cooperation",disputed.state());
        var again=act(owner,disputed,"request-completion");
        String receipt=key();
        var complete=matches.respond(tutor.session(),m.id(),response(again),receipt,"confirm-completion");
        assertEquals(complete,matches.respond(tutor.session(),m.id(),response(again),receipt,"confirm-completion"));
        assertEquals("completed",complete.state());
        assertTrue(complete.reviewEligible());
        assertEquals(complete.completedAt().plus(java.time.Duration.ofDays(14)),complete.reviewDeadlineAt());
        assertEquals(2,db.queryForObject("SELECT count(*) FROM notifications WHERE event_id=? AND event_type='match_completed'",Integer.class,m.roundId()));
        db.update("UPDATE match_rounds SET completed_at=now()-interval '15 days',review_deadline_at=now()-interval '1 day' WHERE id=?",m.roundId());
        assertFalse(matches.detail(owner.session(),m.id()).reviewEligible());
        var grant=contacts.grant(tutor.session(),m.id());
        contacts.withdraw(tutor.session(),m.id(),new WithdrawContacts(m.roundId(),((Number)grant.get("version")).longValue()),key());
        assertEquals(false,contacts.grant(owner.session(),m.id()).get("canViewContacts"));
    }

    @Test void scheduledFollowUpIsOncePerRoundAndAdvancesOnlyDueRequests() {
        var owner=user("REQUESTER"); var tutor=user("TUTOR");
        var m=exchanged(owner,tutor);
        lifecycle.followUp();
        assertNull(db.queryForObject("SELECT followup_reminded_at FROM match_rounds WHERE id=?",java.sql.Timestamp.class,m.roundId()));
        db.update("UPDATE match_rounds SET contacts_exchanged_at=now()-interval '5 days' WHERE id=?",m.roundId());
        lifecycle.followUp(); lifecycle.followUp();
        assertEquals(2,db.queryForObject("SELECT count(*) FROM notifications WHERE event_id=? AND event_type='match_followup'",Integer.class,m.roundId()));
        assertNotNull(db.queryForObject("SELECT followup_reminded_at FROM match_rounds WHERE id=?",java.sql.Timestamp.class,m.roundId()));
        act(tutor,m,"request-cooperation");
        lifecycle.followUp();
        assertEquals("MUTUAL",matches.detail(owner.session(),m.id()).state());
        db.update("UPDATE match_rounds SET cooperation_requested_at=now()-interval '3 days' WHERE id=?",m.roundId());
        lifecycle.followUp();
        var working=matches.detail(owner.session(),m.id());
        assertEquals("in_cooperation",working.state());
        act(owner,working,"request-completion");
        lifecycle.followUp();
        assertNull(matches.detail(tutor.session(),m.id()).completedAt());
        db.update("UPDATE match_rounds SET completion_requested_at=now()-interval '3 days' WHERE id=?",m.roundId());
        lifecycle.followUp(); lifecycle.followUp();
        assertEquals("completed",matches.detail(tutor.session(),m.id()).state());
        assertEquals(2,db.queryForObject("SELECT count(*) FROM notifications WHERE event_id=? AND event_type='match_completed'",Integer.class,m.roundId()));
    }

    @Test void revokedExchangeCancelsPendingCooperationAndNoDealNeverUnlocksReviews() {
        var owner=user("REQUESTER"); var tutor=user("TUTOR");
        var m=exchanged(owner,tutor);
        act(tutor,m,"request-cooperation");
        var grant=contacts.grant(owner.session(),m.id());
        var revoked=contacts.withdraw(owner.session(),m.id(),new WithdrawContacts(m.roundId(),((Number)grant.get("version")).longValue()),key());
        var hidden=matches.detail(tutor.session(),m.id());
        assertFalse(hidden.contactsExchanged()); assertNull(hidden.cooperationRequestedBy());
        assertThrows(ApiError.class,()->act(owner,hidden,"request-cooperation"));
        contacts.agree(owner.session(),m.id(),new AgreeContacts(m.roundId(),((Number)revoked.get("version")).longValue(),
            List.of("WECHAT"),contacts.get(owner.session()).fieldVersions(),true),key());
        var closed=act(owner,act(tutor,hidden,"request-cooperation"),"close-no-deal");
        assertEquals("closed_no_deal",closed.state()); assertNull(closed.reviewDeadlineAt());
        assertEquals(false,contacts.grant(tutor.session(),m.id()).get("canViewContacts"));
        assertThrows(ApiError.class,()->act(tutor,closed,"request-completion"));
    }

    @AfterEach void cleanup(){
        for(UUID id:accounts)db.update("DELETE FROM match_relationships WHERE requester_id=? OR tutor_id=?",id,id);
        for(UUID id:accounts)db.update("DELETE FROM service_requests WHERE owner_id=?",id);
        for(UUID id:accounts)db.update("DELETE FROM accounts WHERE id=?",id);
    }
    @Test void discoveryRequiresRequesterAndOnlyShowsPublishedAllowlistedFields() throws Exception {
        var s=setup();
        assertEquals(401,assertThrows(ApiError.class,()->profiles.publicTeachers(null,null,null,null,null,null,null,false,false)).status);
        assertEquals(403,assertThrows(ApiError.class,()->profiles.publicTeachers(s.tutor().session(),null,null,null,null,null,null,false,false)).status);
        var page=profiles.publicTeachers(s.owner().session(),null,"DRAWING",null,"ONLINE",null,null,false,false);assertEquals(1,((List<?>)page.get("items")).size());
        var projection=profiles.publicTeacher(s.owner().session(),s.tutor().id());
        assertEquals(Set.of("id","avatar_url","profileVersion","displayName","categoryCodes","serviceModes","areaCodes","introduction","priceMin","priceMax","availabilityText","schoolName","collegeMajor","gradeLevel","teachGrades","teachSubjects","yearsExp","teachingStyleTags","casesJson","trialPolicy","resumePdfUrl","resumePdfName","is_school_verified","is_skill_verified"),projection.keySet());
        profiles.visibility(s.tutor().session(),new VersionBody(s.teacherVersion()),false,key());
        assertTrue(((List<?>)profiles.publicTeachers(s.owner().session(),null,null,null,null,null,null,false,false).get("items")).isEmpty());
        assertEquals(404,assertThrows(ApiError.class,()->profiles.publicTeacher(s.owner().session(),s.tutor().id())).status);
    }
    @SuppressWarnings("unchecked")
    @Test void teacherDiscoveryPagesTenProfilesWithoutDuplicatesOrPrivateFields() {
        var owner=user("REQUESTER");var expected=new HashSet<UUID>();var featured=new HashSet<UUID>();
        for(int i=0;i<23;i++){
            var tutor=user("TUTOR");expected.add(tutor.id());
            db.update("UPDATE profiles SET display_name=?,category_codes='[\"DRAWING\"]'::jsonb,service_modes='[\"ONLINE\"]'::jsonb,introduction=?,is_public=true,price_min=100,price_max=150 WHERE account_id=?",
                "分页合成老师"+i,"方向：成人水彩入门。\n擅长：色彩与构图。",tutor.id());
            if (i < 13) verify(tutor, "student_id");
            if (i != 12) db.update("UPDATE profiles SET avatar_url=? WHERE account_id=?", "/api/v1/avatars/" + tutor.id(), tutor.id());
            if (i < 12) featured.add(tutor.id());
        }
        var hidden=user("TUTOR");
        var unverified=user("TUTOR");
        db.update("UPDATE profiles SET display_name='未验证合成老师',category_codes='[\"DRAWING\"]'::jsonb,service_modes='[\"ONLINE\"]'::jsonb,introduction='合成介绍',is_public=true WHERE account_id=?",unverified.id());
        db.update("UPDATE accounts SET email_verified=false WHERE id=?",unverified.id());
        var found=new HashSet<UUID>();String cursor=null;
        for(int size:List.of(10,10,3)){
            var page=profiles.publicTeachers(owner.session(),cursor,"DRAWING",null,"ONLINE",null,null,false,false);
            var teachers=(List<Map<String,Object>>)page.get("items");assertEquals(size,teachers.size());
            for(var teacher:teachers){
                UUID id=(UUID)teacher.get("id");assertTrue(found.add(id));assertTrue(expected.contains(id));
                assertEquals(found.size() <= featured.size(), featured.contains(id));
                assertEquals(profiles.publicTeacher(owner.session(),id),teacher);
                assertEquals("方向：成人水彩入门。\n擅长：色彩与构图。",teacher.get("introduction"));
                for(String field:List.of("email","phone","wechat","account","password_hash","is_public"))assertFalse(teacher.containsKey(field));
            }
            cursor=(String)page.get("nextCursor");if(size==10)assertNotNull(cursor);else assertNull(cursor);
        }
        assertEquals(expected,found);assertFalse(found.contains(hidden.id()));assertFalse(found.contains(unverified.id()));
        assertTrue(((List<?>)profiles.publicTeachers(owner.session(),null,"DRAWING",null,"OFFLINE",null,null,false,false).get("items")).isEmpty());
        assertEquals(422,assertThrows(ApiError.class,()->profiles.publicTeachers(owner.session(),"invalid-cursor",null,null,null,null,null,false,false)).status);
    }
    private void verify(User teacher, String type) {
        db.update("INSERT INTO verifications(id,user_id,verify_type,proof_path,status,reviewed_at) VALUES(?,?,?,?,'approved',now())",
            UUID.randomUUID(), teacher.id(), type, key());
    }

    @Test void teacherDiscoveryCombinesExactLabelsTrialOffersAndSchoolVerification() {
        var owner = user("REQUESTER");
        var tutor = user("TUTOR");
        publish(tutor);
        db.update("""
            UPDATE profiles SET teach_grades='["初一","初二"]', teach_subjects='["物理","数学"]',
                area_codes='["PS_PINGSHAN"]' WHERE account_id=?
            """, tutor.id());
        Supplier<List<?>> filtered = () -> (List<?>) profiles.publicTeachers(owner.session(), null, "DRAWING",
            "PS_PINGSHAN", "ONLINE", "初二", "数学", true, true).get("items");
        verify(tutor, "skill_cert");
        db.update("UPDATE profiles SET trial_policy='按正常时薪试课' WHERE account_id=?", tutor.id());
        assertTrue(filtered.get().isEmpty());
        verify(tutor, "student_id");
        for (String policy : List.of("支持线上免费沟通15分钟", "支持首次试课半价", "按正常时薪试课", "暂不支持试课", "")) {
            db.update("UPDATE profiles SET trial_policy=? WHERE account_id=?", policy, tutor.id());
            assertEquals(policy.isEmpty() || policy.equals("暂不支持试课") ? 0 : 1, filtered.get().size(), policy);
        }
        assertEquals(1, ((List<?>) profiles.publicTeachers(owner.session(), null, null, null, null,
            "初二", "数学", false, false).get("items")).size());
        for (var labels : List.of(List.of("初", "数学"), List.of("初二", "数"))) {
            assertTrue(((List<?>) profiles.publicTeachers(owner.session(), null, null, null, null,
                labels.get(0), labels.get(1), false, false).get("items")).isEmpty());
        }
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
        var changed=profiles.saveTeacher(s.tutor().session(),ResumeFixtures.teacher(s.teacherVersion(),"最新老师名字",List.of("DRAWING"),List.of("ONLINE"),List.of(),"最新自述",null,null,""));
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
