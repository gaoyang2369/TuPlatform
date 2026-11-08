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
        assertDoesNotThrow(() -> Rules.password("密".repeat(128)));
        assertThrows(ApiError.class, () -> Rules.password("密".repeat(129)));
        assertThrows(ApiError.class, () -> Rules.password("short"));
        assertThrows(ApiError.class, () -> Rules.text("displayName", "\u3000", 30, true));
        assertThrows(ApiError.class, () -> Rules.password("\uD800".repeat(15)));
    }
    @Test void accountAndEmailNormalizationDoNotRemovePlusOrDots() {
        assertEquals("teacher_1", Rules.account("Teacher_1"));
        assertEquals("a.b+class@example.com", Rules.email("A.B+Class@Example.COM"));
        assertThrows(ApiError.class, () -> Rules.account("1teacher"));
        assertThrows(ApiError.class, () -> Rules.email("a\r\n@example.com"));
    }
    @Test void decimalCapacityAndRangeAreExact() {
        assertEquals(new BigDecimal("999999999999.99"), Rules.money("priceMin", "999999999999.99"));
        for (String invalid : List.of("0", "-1", "1.001", "1e2", "1000000000000", "NaN"))
            assertThrows(ApiError.class, () -> Rules.money("priceMin", invalid));
        assertThrows(ApiError.class, () -> Rules.priceRange(BigDecimal.ONE, null));
        assertThrows(ApiError.class, () -> Rules.priceRange(BigDecimal.TEN, BigDecimal.ONE));
    }
    @Test void selectionsRespectConfiguredCategoriesAndRequiredOfflineArea() {
        assertThrows(ApiError.class, () -> Rules.choices("categoryCodes", List.of("DRAWING"), Set.of(), true));
        assertThrows(ApiError.class, () -> Rules.choices("serviceModes", List.of("ONLINE", "ONLINE"), Rules.MODES, true));
        var service = new ProfileService(null, null, null, null, "DRAWING");
        assertDoesNotThrow(() -> service.validateTeacher(new TeacherBody(0L, "老师", List.of("DRAWING"), List.of("ONLINE"), List.of(), "自述", null, null, ""), true));
        assertThrows(ApiError.class, () -> service.validateTeacher(new TeacherBody(0L, "老师", List.of("DRAWING"), List.of("OFFLINE"), List.of(), "自述", null, null, ""), true));
        assertDoesNotThrow(() -> service.validateTeacher(new TeacherBody(0L, "", List.of(), List.of(), List.of(), "", null, null, ""), false));
    }
}
