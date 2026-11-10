package cn.linke;

import java.util.*;

record ContactFields(Long phone, Long wechat) {}
record Contacts(long version, String phone, String wechat, ContactFields fieldVersions) {}
record SaveContacts(Long expectedVersion, String phone, String wechat) {}
record AgreeContacts(UUID roundId, Long expectedGrantVersion, List<String> selectedFields,
                     ContactFields expectedContactFieldVersions, Boolean agree) {}
record WithdrawContacts(UUID roundId, Long expectedGrantVersion) {}

final class ContactRules {
    private ContactRules() {}
    static String phone(String value) {
        if (value == null || value.isEmpty()) return null;
        if (!value.matches("1[0-9]{10}")) throw ApiError.invalid("phone", "手机号暂支持+86，请填写以1开头的11位数字");
        return value;
    }
    static String wechat(String value) {
        if (value == null || value.isEmpty()) return null;
        if (value.codePointCount(0,value.length()) > 64 || value.codePoints().anyMatch(c ->
            Character.isWhitespace(c) || Character.isSpaceChar(c) || Character.isISOControl(c) || Character.getType(c)==Character.FORMAT))
            throw ApiError.invalid("wechat", "微信号须为1—64个字符，不含空格、换行或控制字符，请本人核对");
        return value;
    }
    static List<String> fields(List<String> fields) {
        if (fields == null || fields.isEmpty() || fields.size()>2 || fields.stream().anyMatch(f -> !"PHONE".equals(f) && !"WECHAT".equals(f)) || new HashSet<>(fields).size()!=fields.size())
            throw ApiError.invalid("selectedFields", "请至少选择一项本人已填写的手机号或微信");
        return fields.stream().sorted().toList();
    }
}
