package cn.linke;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MatchRulesTest {
    @Test void optionalInvitationMessagePreservesExistingReceiptFingerprints() throws Exception {
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        var request=java.util.UUID.randomUUID();var submission=java.util.UUID.randomUUID();
        var legacy="{\"requestId\":\""+request+"\",\"expectedSubmissionId\":\""+submission+"\",\"kind\":\"APPLICATION\",\"tutorUserId\":null,\"expectedTeacherProfileVersion\":2}";
        assertEquals(legacy,json.writeValueAsString(new StartMatch(request,submission,"APPLICATION",null,2L)));
        assertEquals("{\"roundId\":\""+submission+"\",\"expectedVersion\":2}",json.writeValueAsString(new RespondMatch(submission,2L)));
    }
    @Test void onlyRecipientMayAcceptOrRejectAndOnlyInitiatorMayWithdraw() {
        for(String action:java.util.List.of("ACCEPT","REJECT")) {
            assertDoesNotThrow(()->MatchRules.response("WAITING",false,action));
            assertEquals(403,assertThrows(ApiError.class,()->MatchRules.response("WAITING",true,action)).status);
        }
        assertDoesNotThrow(()->MatchRules.response("WAITING",true,"WITHDRAW"));
        assertEquals(403,assertThrows(ApiError.class,()->MatchRules.response("WAITING",false,"WITHDRAW")).status);
    }
    @Test void endedOrMutualIntentionCannotBeRespondedToOrWithdrawn() {
        for(String state:java.util.List.of("MUTUAL","REJECTED","WITHDRAWN"))for(String action:java.util.List.of("ACCEPT","REJECT","WITHDRAW"))
            assertEquals(409,assertThrows(ApiError.class,()->MatchRules.response(state,true,action)).status);
    }
}
