package cn.linke;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

final class DraftRules {
    private static final Set<String> FIELDS = Set.of("title","categoryCode","audienceStageCode","areaCode","goal","serviceMode","budgetMin","budgetMax","durationHours","frequency","availableSlots","availabilityNote","expectedStartDate","tutorExpectations");
    static void checkNames(ObjectNode values) {
        for (var names = values.fieldNames(); names.hasNext();) { String name = names.next(); if (!FIELDS.contains(name)) fail(name,"不支持的草稿字段"); }
    }
    static void validate(ObjectNode values, Set<String> categories) {
        checkNames(values);
        text(values,"title",60); text(values,"goal",2000); text(values,"availabilityNote",300); text(values,"tutorExpectations",500);
        choice(values,"categoryCode",categories); choice(values,"audienceStageCode",Set.of("PRIMARY","JUNIOR","SENIOR","ADULT","OTHER"));
        choice(values,"areaCode",Rules.AREAS); choice(values,"serviceMode",Rules.MODES);
        BigDecimal min = money(values,"budgetMin"), max = money(values,"budgetMax");
        if (min != null && max != null && max.compareTo(min)<0) fail("budgetMax","预算上限不能低于下限");
        if (values.has("durationHours")) {
            String value = string(values,"durationHours");
            if (!value.matches("[0-9]{1,2}(\\.[0-9]{1,2})?")) fail("durationHours","请按小时填写有效时长");
            BigDecimal duration = new BigDecimal(value);
            if (duration.compareTo(new BigDecimal("0.5"))<0 || duration.compareTo(new BigDecimal("8"))>0 || duration.remainder(new BigDecimal("0.5")).signum()!=0)
                fail("durationHours","单次时长须为0.5—8小时，步长0.5小时");
        }
        if (values.has("expectedStartDate")) {
            String value = string(values,"expectedStartDate");
            try { if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") || !LocalDate.parse(value).toString().equals(value)) fail("expectedStartDate","请填写有效日期"); }
            catch (java.time.DateTimeException e) { fail("expectedStartDate","请填写有效日期"); }
            // The not-before-today rule is a submission rule, not a reason to lose an older draft.
        }
        if (values.has("frequency")) {
            JsonNode frequency = values.get("frequency");
            if (!frequency.isObject()) fail("frequency","请填写有效频率");
            for (var it = frequency.fieldNames(); it.hasNext();) if (!Set.of("mode","count").contains(it.next())) fail("frequency","不支持的频率字段");
            String mode = frequency.path("mode").asText("");
            if (!Set.of("PER_WEEK","NEGOTIABLE").contains(mode)) fail("frequency","请选择每周次数或可协商");
            JsonNode count = frequency.get("count");
            if (mode.equals("NEGOTIABLE") && count!=null && !count.isNull()) fail("frequency","可协商时不填写每周次数");
            if (mode.equals("PER_WEEK") && count!=null && !count.isNull() && (!count.isIntegralNumber() || !count.canConvertToInt() || count.intValue()<1 || count.intValue()>14))
                fail("frequency","每周次数须为1—14次");
        }
        if (values.has("availableSlots")) {
            JsonNode slots = values.get("availableSlots");
            if (!slots.isArray() || slots.size()>21) fail("availableSlots","最多填写21项时段");
            var byDay = new HashMap<Integer,List<int[]>>();
            for (JsonNode slot : slots) {
                if (!slot.isObject() || slot.size()!=3 || !slot.has("weekday") || !slot.has("startMinute") || !slot.has("endMinute")) fail("availableSlots","请补全每项时段或删除未填写的时段");
                for (String key : List.of("weekday","startMinute","endMinute")) if (!slot.get(key).isIntegralNumber() || !slot.get(key).canConvertToInt()) fail("availableSlots","星期和时刻必须是有效整数");
                int day=slot.get("weekday").intValue(), start=slot.get("startMinute").intValue(), end=slot.get("endMinute").intValue();
                if (day<1 || day>7 || start<0 || start>1439 || end<1 || end>1440 || start>=end) fail("availableSlots","时段须在同一天内，跨午夜请拆开；24:00仅可作结束");
                List<int[]> periods = byDay.computeIfAbsent(day, ignored -> new ArrayList<>());
                for (int[] prior : periods) if (start<prior[1] && prior[0]<end) fail("availableSlots","同一天的时段不能重复或重叠，相邻时段可以保留");
                periods.add(new int[]{start,end});
            }
        }
    }
    private static String string(ObjectNode values, String name) { if (!values.get(name).isTextual()) fail(name,"请填写文本格式的值"); return values.get(name).textValue(); }
    private static void text(ObjectNode values,String name,int maximum) { if(values.has(name)) Rules.text(name,string(values,name),maximum,false); }
    private static void choice(ObjectNode values,String name,Set<String> allowed) { if(values.has(name) && !allowed.contains(string(values,name))) fail(name,"请选择当前允许的选项"); }
    private static BigDecimal money(ObjectNode values,String name) {
        if(!values.has(name)) return null;
        String value=string(values,name); if(value.isEmpty()) fail(name,"请填写金额或清空此项");
        return Rules.money(name,value);
    }
    private static void fail(String field,String message) { throw ApiError.invalid(field,message); }
    private DraftRules() {}
}
