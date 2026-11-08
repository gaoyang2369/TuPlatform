package cn.linke;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class Rules {
    static final Set<String> CATEGORIES = Set.of("DRAWING", "DANCE", "OTHER_SKILL", "EXTRACURRICULAR_STUDY");
    static final Set<String> MODES = Set.of("ONLINE", "OFFLINE");
    static final Set<String> AREAS = Set.of("PS_PINGSHAN", "PS_KENGZI", "PS_LONGTIAN", "PS_SHIJING", "PS_MALUAN", "PS_BILING");
    static String text(String field, String value, int max, boolean required) {
        if (value == null) value = "";
        if (value.codePointCount(0, value.length()) > max || value.indexOf('\0') >= 0 ||
            value.codePoints().anyMatch(cp -> cp >= 0xD800 && cp <= 0xDFFF))
            throw ApiError.invalid(field, "最多" + max + "个字符，且不能包含无效字符");
        if (required && value.codePoints().allMatch(cp -> Character.isWhitespace(cp) || Character.isSpaceChar(cp)))
            throw ApiError.invalid(field, "请填写此项");
        return value;
    }
    static String account(String value) {
        if (value == null || !value.matches("[A-Za-z][A-Za-z0-9_]{3,31}"))
            throw ApiError.invalid("account", "账号须为4—32位字母、数字或下划线，且以字母开头");
        return value.toLowerCase(Locale.ROOT);
    }
    static String email(String value) {
        if (value == null || value.length() > 254 || !value.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+") || value.chars().anyMatch(Character::isISOControl))
            throw ApiError.invalid("email", "请填写有效邮箱");
        return value.toLowerCase(Locale.ROOT);
    }
    static void password(String value) {
        if (value == null || value.codePointCount(0, value.length()) < 15 || value.codePointCount(0, value.length()) > 128 ||
            value.codePoints().anyMatch(cp -> cp >= 0xD800 && cp <= 0xDFFF))
            throw ApiError.invalid("password", "密码须为15—128个字符，允许空格，不自动删减");
    }
    static List<String> choices(String field, List<String> values, Set<String> allowed, boolean required) {
        if (values == null) values = List.of();
        if ((required && values.isEmpty()) || values.size() > allowed.size() || values.stream().anyMatch(v -> v == null || !allowed.contains(v)) || values.stream().distinct().count() != values.size())
            throw ApiError.invalid(field, "请选择当前允许的选项，不能重复");
        return List.copyOf(values);
    }
    static BigDecimal money(String field, String value) {
        if (value == null || value.isEmpty()) return null;
        if (!value.matches("[0-9]{1,12}(\\.[0-9]{1,2})?")) throw ApiError.invalid(field, "请填写有效金额，最多两位小数及12位整数");
        var amount = new BigDecimal(value);
        if (amount.signum() <= 0) throw ApiError.invalid(field, "金额须大于0");
        return amount;
    }
    static void priceRange(BigDecimal min, BigDecimal max) {
        if ((min == null) != (max == null) || (min != null && max.compareTo(min) < 0))
            throw ApiError.invalid("priceMax", "报价须同时填写上下限，且上限不低于下限；均不填表示面议");
    }
    private Rules() {}
}
