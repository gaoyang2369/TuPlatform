package cn.linke;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ContactRulesTest {
    @Test void phoneIsOptionalAndExplicitlyMainland() {
        assertNull(ContactRules.phone(null));assertNull(ContactRules.phone(""));assertEquals("13800000000",ContactRules.phone("13800000000"));
        for(String value:List.of(" 13800000000","+8613800000000","23800000000","1380000000","１３８００００００００"))assertThrows(ApiError.class,()->ContactRules.phone(value));
    }
    @Test void wechatCountsCodePointsAndRejectsWhitespaceAndControlsWithoutTrimming() {
        assertNull(ContactRules.wechat(""));assertEquals("微".repeat(64),ContactRules.wechat("微".repeat(64)));
        assertEquals("😀".repeat(64),ContactRules.wechat("😀".repeat(64)));
        for(String value:List.of("微".repeat(65)," x","x\nx","x\tx","x\u0000x","x\u00a0x","x\u200bx"))assertThrows(ApiError.class,()->ContactRules.wechat(value));
    }
    @Test void onlyExplicitNonemptyPhoneOrWechatSelectionsAreAccepted() {
        assertEquals(List.of("PHONE","WECHAT"),ContactRules.fields(List.of("WECHAT","PHONE")));
        assertThrows(ApiError.class,()->ContactRules.fields(null));assertThrows(ApiError.class,()->ContactRules.fields(List.of()));
        assertThrows(ApiError.class,()->ContactRules.fields(List.of("EMAIL")));assertThrows(ApiError.class,()->ContactRules.fields(List.of("PHONE","PHONE")));
    }
}
