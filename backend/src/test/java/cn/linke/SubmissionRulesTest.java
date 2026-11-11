package cn.linke;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SubmissionRulesTest {
    private final ObjectMapper json=new ObjectMapper();
    private ObjectNode complete() throws Exception { return (ObjectNode)json.readTree("""
        {"title":"合成绘画需求","categoryCode":"DRAWING","audienceStageCode":"ADULT","areaCode":"PS_PINGSHAN",
         "goal":"绘画基础","budgetMin":"1000.01","budgetMax":"1500.99","durationHours":"1.5",
         "frequency":{"mode":"NEGOTIABLE","count":null},"availableSlots":[{"weekday":1,"startMinute":600,"endMinute":1440}]}
        """); }
    private final OffsetDateTime now=OffsetDateTime.parse("2026-11-10T16:00:00+08:00");
    @Test void expiryUsesBeijingDateAndDoesNotDependOnSubmissionTimeOfDay() {
        assertEquals(OffsetDateTime.parse("2026-11-21T00:00:00+08:00"),SubmissionRules.expiresAt(OffsetDateTime.parse("2026-11-06T23:59:59+08:00")));
        assertEquals(OffsetDateTime.parse("2027-01-16T00:00:00+08:00"),SubmissionRules.expiresAt(OffsetDateTime.parse("2026-12-31T16:00:00Z")));
    }
    @Test void requiresCompleteFieldsAndAnExplicitCurrentDeclaration() throws Exception {
        SubmitRequest body=new SubmitRequest(1L,"SELF_ADULT","publisher-v1");
        assertDoesNotThrow(()->SubmissionRules.validate(complete(),Set.of("DRAWING"),"合成需求方",body,now));
        ObjectNode missing=complete();missing.remove("goal");
        assertThrows(ApiError.class,()->SubmissionRules.validate(missing,Set.of("DRAWING"),"合成需求方",body,now));
        ObjectNode blank=complete();blank.put("goal","\u00a0\u3000");
        assertThrows(ApiError.class,()->SubmissionRules.validate(blank,Set.of("DRAWING"),"合成需求方",body,now));
        assertThrows(ApiError.class,()->SubmissionRules.validate(complete(),Set.of("DRAWING")," ",body,now));
        assertThrows(ApiError.class,()->SubmissionRules.validate(complete(),Set.of("DRAWING"),"需求方",new SubmitRequest(1L,null,null),now));
        ObjectNode count=complete();count.putObject("frequency").put("mode","PER_WEEK");
        assertThrows(ApiError.class,()->SubmissionRules.validate(count,Set.of("DRAWING"),"需求方",body,now));
    }
    @Test void declarationMustMatchAudienceAndCannotBeCopiedWithoutConfirmation() throws Exception {
        for(String stage:Set.of("PRIMARY","JUNIOR","SENIOR","ADULT","OTHER")) {
            for(String declaration:Set.of("SELF_ADULT","GUARDIAN_OF_MINOR")) {
                ObjectNode f=complete();f.put("audienceStageCode",stage);
                boolean allowed=stage.equals("OTHER")||(stage.equals("ADULT")==declaration.equals("SELF_ADULT"));
                if(allowed)assertDoesNotThrow(()->SubmissionRules.validate(f,Set.of("DRAWING"),"需求方",new SubmitRequest(1L,declaration,"publisher-v1"),now));
                else assertThrows(ApiError.class,()->SubmissionRules.validate(f,Set.of("DRAWING"),"需求方",new SubmitRequest(1L,declaration,"publisher-v1"),now));
            }
        }
    }
    @Test void startDateUsesBeijingTodayAndClosedCategoriesCannotSubmit() throws Exception {
        ObjectNode f=complete();f.put("expectedStartDate","2026-11-10");
        var body=new SubmitRequest(1L,"SELF_ADULT","publisher-v1");
        assertDoesNotThrow(()->SubmissionRules.validate(f,Set.of("DRAWING"),"需求方",body,now));
        f.put("expectedStartDate","2026-11-09");
        assertThrows(ApiError.class,()->SubmissionRules.validate(f,Set.of("DRAWING"),"需求方",body,now));
        assertThrows(ApiError.class,()->SubmissionRules.validate(complete(),Set.of(),"需求方",body,now));
    }
    @Test void communityLocationAndNegotiableTimesCanSubmitWhileStudyNeedsGradeAndSubject() throws Exception {
        var body=new SubmitRequest(1L,"SELF_ADULT","publisher-v1");
        ObjectNode fields=complete();fields.remove("areaCode");fields.remove("availableSlots");
        fields.put("locationText","坪山区 · 测试花园");
        var preference=fields.putObject("availabilityPreference");preference.put("mode","NEGOTIABLE");preference.putArray("weekdays");preference.putArray("periods");
        assertDoesNotThrow(()->SubmissionRules.validate(fields,Set.of("DRAWING"),"需求方",body,now));
        fields.remove("locationText");
        assertThrows(ApiError.class,()->SubmissionRules.validate(fields,Set.of("DRAWING"),"需求方",body,now));
        fields.put("serviceMode","ONLINE");
        assertDoesNotThrow(()->SubmissionRules.validate(fields,Set.of("DRAWING"),"需求方",body,now));
        preference.put("mode","PREFERRED");
        assertThrows(ApiError.class,()->SubmissionRules.validate(fields,Set.of("DRAWING"),"需求方",body,now));
        preference.withArray("weekdays").add(6).add(7);preference.withArray("periods").add("AFTERNOON");
        fields.put("categoryCode","EXTRACURRICULAR_STUDY");
        assertThrows(ApiError.class,()->SubmissionRules.validate(fields,Set.of("EXTRACURRICULAR_STUDY"),"需求方",body,now));
        fields.put("grade","成人学习");
        assertThrows(ApiError.class,()->SubmissionRules.validate(fields,Set.of("EXTRACURRICULAR_STUDY"),"需求方",body,now));
        fields.put("subject","英语");
        assertDoesNotThrow(()->SubmissionRules.validate(fields,Set.of("EXTRACURRICULAR_STUDY"),"需求方",body,now));
    }
}
