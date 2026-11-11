package cn.linke;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DraftRulesTest {
    private final ObjectMapper json=new ObjectMapper();
    private ObjectNode value(String source) throws Exception { return (ObjectNode)json.readTree(source); }
    @Test void incompleteDraftAndSingleBudgetEndAreAllowed() throws Exception {
        assertDoesNotThrow(()->DraftRules.validate(value("{}"),Set.of()));
        assertDoesNotThrow(()->DraftRules.validate(value("{\"budgetMin\":\"1000.01\",\"frequency\":{\"mode\":\"PER_WEEK\",\"count\":null}}"),Set.of()));
        assertDoesNotThrow(()->DraftRules.validate(value("{\"categoryCode\":\"DRAWING\",\"durationHours\":\"0.5\",\"expectedStartDate\":\"2020-01-01\"}"),Set.of("DRAWING")));
    }
    @Test void rejectsBadMoneyDurationAndDatesWithoutRounding() {
        for(String invalid:java.util.List.of("{\"budgetMin\":\"0\"}","{\"budgetMin\":\"1.001\"}","{\"budgetMin\":1.5}","{\"budgetMin\":\"2\",\"budgetMax\":\"1\"}","{\"durationHours\":\"0.75\"}","{\"durationHours\":\"8.5\"}","{\"expectedStartDate\":\"2026-02-30\"}","{\"frequency\":{\"mode\":\"PER_WEEK\",\"count\":1.5}}"))
            assertThrows(ApiError.class,()->DraftRules.validate(value(invalid),Set.of("DRAWING")));
    }
    @Test void adjacentSlotsAnd2400EndAreAllowedButOverlapsAndMidnightCrossingAreNot() throws Exception {
        assertDoesNotThrow(()->DraftRules.validate(value("{\"availableSlots\":[{\"weekday\":1,\"startMinute\":600,\"endMinute\":660},{\"weekday\":1,\"startMinute\":660,\"endMinute\":1440}]}"),Set.of()));
        for(String invalid:java.util.List.of("{\"availableSlots\":[{\"weekday\":1,\"startMinute\":600,\"endMinute\":660},{\"weekday\":1,\"startMinute\":650,\"endMinute\":700}]}","{\"availableSlots\":[{\"weekday\":1,\"startMinute\":1440,\"endMinute\":1440}]}","{\"availableSlots\":[{\"weekday\":1,\"startMinute\":1380,\"endMinute\":60}]}"))
            assertThrows(ApiError.class,()->DraftRules.validate(value(invalid),Set.of()));
    }
    @Test void unknownOrClosedFieldsCannotBeSaved() {
        assertThrows(ApiError.class,()->DraftRules.checkNames(value("{\"ownerId\":null}")));
        assertThrows(ApiError.class,()->DraftRules.validate(value("{\"publisherDeclaration\":\"SELF_ADULT\"}"),Set.of()));
        assertThrows(ApiError.class,()->DraftRules.validate(value("{\"categoryCode\":\"DRAWING\"}"),Set.of()));
    }
    @Test void broadPreferencesAndStudyDetailsAreValidatedWithoutInventingExactTimes() throws Exception {
        assertDoesNotThrow(()->DraftRules.validate(value("""
            {"locationText":"坪山区 · 某某花园","grade":"高二","subject":"数学",
             "availabilityPreference":{"mode":"PREFERRED","weekdays":[6,7],"periods":["AFTERNOON","EVENING"]},"availableSlots":[]}
            """),Set.of()));
        assertDoesNotThrow(()->DraftRules.validate(value("{\"availabilityPreference\":{\"mode\":\"NEGOTIABLE\",\"weekdays\":[],\"periods\":[]}}"),Set.of()));
        for(String invalid:java.util.List.of(
            "{\"availabilityPreference\":{\"mode\":\"PREFERRED\",\"weekdays\":[6,6],\"periods\":[]}}",
            "{\"availabilityPreference\":{\"mode\":\"PREFERRED\",\"weekdays\":[8],\"periods\":[]}}",
            "{\"availabilityPreference\":{\"mode\":\"PREFERRED\",\"weekdays\":[6],\"periods\":[\"MIDNIGHT\"]}}",
            "{\"availabilityPreference\":{\"mode\":\"NEGOTIABLE\",\"weekdays\":[6],\"periods\":[]}}",
            "{\"availabilityPreference\":{\"mode\":\"NEGOTIABLE\",\"weekdays\":[],\"periods\":[]},\"availableSlots\":[{\"weekday\":6,\"startMinute\":600,\"endMinute\":660}]}"))
            assertThrows(ApiError.class,()->DraftRules.validate(value(invalid),Set.of()));
        assertThrows(ApiError.class,()->DraftRules.validate(json.createObjectNode().put("locationText","地".repeat(81)),Set.of()));
        assertThrows(ApiError.class,()->DraftRules.validate(json.createObjectNode().put("grade","级".repeat(21)),Set.of()));
    }
}
