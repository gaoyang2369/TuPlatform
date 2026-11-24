package cn.linke;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

final class RequestLocation {
    static final Map<String,String> STREETS = Map.of("PS_PINGSHAN","坪山街道","PS_KENGZI","坑梓街道",
        "PS_LONGTIAN","龙田街道","PS_SHIJING","石井街道","PS_MALUAN","马峦街道","PS_BILING","碧岭街道");
    static final List<String> HINTS = List.of("中心公园附近","招商花园城片区","坪山片区","坑梓片区",
        "龙田片区","石井片区","马峦片区","碧岭片区");
    static String street(ObjectNode fields) {
        String street = fields.path("street").asText("");
        return STREETS.containsValue(street) ? street : STREETS.getOrDefault(fields.path("areaCode").asText(""), "");
    }
    static void publicFields(ObjectNode saved, Map<String,Object> result) {
        String street = street(saved);
        if (!street.isEmpty()) result.put("street", street);
        String hint = saved.path("area_hint").asText("");
        if (HINTS.contains(hint)) result.put("area_hint", hint);
        else if (saved.hasNonNull("locationText") && !street.isEmpty())
            result.put("area_hint", street.replace("街道", "片区"));
    }
    private RequestLocation() {}
}
