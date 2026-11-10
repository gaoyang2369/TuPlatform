package cn.linke;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
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
@SpringBootTest(properties={"app.request-categories=DRAWING"})
class DraftDatabaseTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        String url=System.getenv("TEST_DB_URL");
        if(url==null || !url.matches("jdbc:postgresql://[^/]+/[A-Za-z0-9_]+_test(?:\\?.*)?"))throw new IllegalStateException("Dedicated _test database required");
        properties.add("spring.datasource.url",()->url); properties.add("spring.datasource.username",()->System.getenv("TEST_DB_USERNAME")); properties.add("spring.datasource.password",()->System.getenv("TEST_DB_PASSWORD"));
    }
    @Autowired AuthService auth; @Autowired RequestDraftService drafts; @Autowired JdbcTemplate db; @Autowired ObjectMapper json;
    @MockitoBean MailGateway mail;
    private String token; private final List<UUID> accounts=new ArrayList<>();
    @BeforeEach void mail(){when(mail.send(anyString(),anyString(),eq(false))).thenAnswer(call->{token=call.getArgument(1);return true;});}
    @AfterEach void cleanup(){for(UUID id:accounts)db.update("DELETE FROM accounts WHERE id=?",id);}
    private String user(String identity){String name="test_draft_"+UUID.randomUUID().toString().replace("-","").substring(0,18);String password="Synthetic draft password 2026";Registration r=auth.register(new RegisterBody(name,password,name+"@example.invalid",true));auth.verify(new VerifyBody(token,null),r.context());var login=auth.login(new LoginBody(name,password));accounts.add(login.me().id());auth.identity(login.session(),new IdentityBody(identity));return login.session();}
    private DraftPatch patch(long version,String fields)throws Exception{return new DraftPatch(version,(ObjectNode)json.readTree(fields));}
    @Test void partialStepSavesPreserveFieldsAndCreationIsIdempotent()throws Exception{
        String session=user("REQUESTER"),key=UUID.randomUUID().toString();SavedDraft first=drafts.create(session,key);assertEquals(first.id(),drafts.create(session,key).id());
        var basic=drafts.save(session,first.id(),patch(0,"{\"title\":\"合成草稿\",\"goal\":\"保留第一步\",\"categoryCode\":\"DRAWING\"}"));
        var second=drafts.save(session,first.id(),patch(basic.version(),"{\"budgetMin\":\"1000.01\",\"durationHours\":\"0.5\"}"));
        assertEquals("合成草稿",second.fields().path("title").asText());assertEquals("保留第一步",second.fields().path("goal").asText());assertFalse(second.fields().has("budgetMax"));
        var cleared=drafts.save(session,first.id(),patch(second.version(),"{\"goal\":null}"));assertFalse(cleared.fields().has("goal"));assertEquals("DRAFT",cleared.status());
    }
    @Test void ownershipRolesAndOldVersionsAreEnforced()throws Exception{
        String owner=user("REQUESTER"),other=user("REQUESTER"),teacher=user("TUTOR");SavedDraft d=drafts.create(owner,UUID.randomUUID().toString());
        assertEquals(404,assertThrows(ApiError.class,()->drafts.get(other,d.id())).status);
        assertEquals(404,assertThrows(ApiError.class,()->drafts.save(other,d.id(),patch(0,"{}"))).status);
        assertEquals(403,assertThrows(ApiError.class,()->drafts.create(teacher,UUID.randomUUID().toString())).status);
        drafts.save(owner,d.id(),patch(0,"{\"title\":\"新版本\"}"));
        assertEquals(409,assertThrows(ApiError.class,()->drafts.save(owner,d.id(),patch(0,"{\"title\":\"旧版本覆盖\"}"))).status);
        assertEquals("新版本",drafts.get(owner,d.id()).fields().path("title").asText());
    }
    @Test void invalidSaveLeavesDatabaseContentAndVersionUntouched()throws Exception{
        String owner=user("REQUESTER");SavedDraft d=drafts.create(owner,UUID.randomUUID().toString());
        assertThrows(ApiError.class,()->drafts.save(owner,d.id(),patch(0,"{\"budgetMin\":\"1.001\"}")));
        assertEquals(0,drafts.get(owner,d.id()).version());assertTrue(drafts.get(owner,d.id()).fields().isEmpty());
        db.update("UPDATE service_requests SET status='PENDING_REVIEW' WHERE id=?",d.id());
        assertEquals(409,assertThrows(ApiError.class,()->drafts.save(owner,d.id(),patch(0,"{}"))).status);
    }
    @Test void retentionReminderAndCleanupDoNotRecreateDeletedDraftOnRetry(){
        String owner=user("REQUESTER"),key=UUID.randomUUID().toString();SavedDraft d=drafts.create(owner,key);
        db.update("UPDATE service_requests SET last_saved_at=now()-interval '84 days' WHERE id=?",d.id());assertTrue(drafts.get(owner,d.id()).retentionReminder());
        db.update("UPDATE service_requests SET last_saved_at=now()-interval '91 days' WHERE id=?",d.id());assertEquals(404,assertThrows(ApiError.class,()->drafts.get(owner,d.id())).status);
        drafts.cleanupExpiredDrafts();assertEquals(410,assertThrows(ApiError.class,()->drafts.create(owner,key)).status);
    }
}
