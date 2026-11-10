package cn.linke;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MatchRulesTest {
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
