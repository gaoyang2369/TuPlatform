package cn.linke;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RulesTest {
    @Test void passwordCountsCodePointsAndPreservesSpaces() {
        assertDoesNotThrow(() -> Rules.password("🙂".repeat(15)));
        assertDoesNotThrow(() -> Rules.password(" ".repeat(15)));
        assertDoesNotThrow(() -> Rules.password("密".repeat(64)));
        assertDoesNotThrow(() -> Rules.password("🙂".repeat(8)));
        assertDoesNotThrow(() -> Rules.password("1234 678"));
        assertThrows(ApiError.class, () -> Rules.password("密".repeat(65)));
        assertThrows(ApiError.class, () -> Rules.password("1234567"));
        assertThrows(ApiError.class, () -> Rules.password("short"));
        assertThrows(ApiError.class, () -> Rules.text("displayName", "\u3000", 30, true));
        assertThrows(ApiError.class, () -> Rules.password("\uD800".repeat(15)));
    }
    @Test void accountAndEmailNormalizationDoNotRemovePlusOrDots() {
        assertEquals("teacher_1", Rules.account("Teacher_1"));
        assertEquals("a.b+class@example.com", Rules.email("A.B+Class@Example.COM"));
        assertThrows(ApiError.class, () -> Rules.account("1teacher"));
        assertDoesNotThrow(() -> Rules.account("a".repeat(4)));
        assertDoesNotThrow(() -> Rules.account("a".repeat(16)));
        assertThrows(ApiError.class, () -> Rules.account("a".repeat(3)));
        assertThrows(ApiError.class, () -> Rules.account("a".repeat(17)));
        assertThrows(ApiError.class, () -> Rules.email("a\r\n@example.com"));
    }
    @Test void decimalCapacityAndRangeAreExact() {
        assertEquals(new BigDecimal("999999999999.99"), Rules.money("priceMin", "999999999999.99"));
        for (String invalid : List.of("0", "-1", "1.001", "1e2", "1000000000000", "NaN"))
            assertThrows(ApiError.class, () -> Rules.money("priceMin", invalid));
        assertThrows(ApiError.class, () -> Rules.priceRange(BigDecimal.ONE, null));
        assertThrows(ApiError.class, () -> Rules.priceRange(BigDecimal.TEN, BigDecimal.ONE));
    }
    @Test void structuredResumeIsRequiredOnSaveAndLimitsStylesAndCases() {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var body = ResumeFixtures.teacher(0L, "", List.of(), List.of(), List.of(), "", null, null, "");
        ProfileService.validateResume(body);
        var source = json.valueToTree(body);
        var invalid = java.util.Map.of("teachGrades", List.of(" "), "teachSubjects", List.of(),
            "teachingStyleTags", List.of("耐心细致","擅长基础巩固","习惯培养","备课认真"),
            "trialPolicy", "任意政策", "casesJson", List.of());
        invalid.forEach((field, value) -> {
            var changed = (com.fasterxml.jackson.databind.node.ObjectNode) source.deepCopy();
            changed.set(field, json.valueToTree(value));
            ApiError error = assertThrows(ApiError.class, () -> ProfileService.validateResume(json.convertValue(changed, TeacherBody.class)));
            assertTrue(error.fields.containsKey(field));
        });
        for (var cases : List.of(java.util.Collections.nCopies(4, body.casesJson().getFirst()),
                                  List.of(new TeachingCase("小学","色彩"," ")))) {
            var changed = (com.fasterxml.jackson.databind.node.ObjectNode) source.deepCopy();
            changed.set("casesJson", json.valueToTree(cases));
            assertThrows(ApiError.class, () -> ProfileService.validateResume(json.convertValue(changed, TeacherBody.class)));
        }
    }

    @Test void selectionsRespectConfiguredCategoriesAndRequiredOfflineArea() {
        assertThrows(ApiError.class, () -> Rules.choices("categoryCodes", List.of("DRAWING"), Set.of(), true));
        assertThrows(ApiError.class, () -> Rules.choices("serviceModes", List.of("ONLINE", "ONLINE"), Rules.MODES, true));
        var service = new ProfileService(null, null, null, null, "DRAWING", "试点大学");
        assertDoesNotThrow(() -> service.validateTeacher(ResumeFixtures.teacher(0L, "老师", List.of("DRAWING"), List.of("ONLINE"), List.of(), "自述", null, null, ""), true));
        assertThrows(ApiError.class, () -> service.validateTeacher(ResumeFixtures.teacher(0L, "老师", List.of("DRAWING"), List.of("OFFLINE"), List.of(), "自述", null, null, ""), true));
        assertDoesNotThrow(() -> service.validateTeacher(ResumeFixtures.teacher(0L, "", List.of(), List.of(), List.of(), "", null, null, ""), false));
    }
}
