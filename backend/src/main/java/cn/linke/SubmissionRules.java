package cn.linke;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.*;
import java.util.Set;

final class SubmissionRules {
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    static OffsetDateTime expiresAt(OffsetDateTime submittedAt) {
        return submittedAt.atZoneSameInstant(ZONE).toLocalDate().plusDays(15).atStartOfDay(ZONE).toOffsetDateTime();
    }
    static void validate(ObjectNode fields, Set<String> categories, String displayName,
                         SubmitRequest body, OffsetDateTime now) {
        DraftRules.validate(fields, categories);
        Rules.text("displayName", displayName, 30, true);
        for (String name : Set.of("title","categoryCode","audienceStageCode","goal","budgetMin","budgetMax","durationHours")) {
            if (!fields.hasNonNull(name) || fields.path(name).asText().isBlank()) throw ApiError.invalid(name,"提交前请补全此项");
        }
        Rules.text("title",fields.path("title").asText(),60,true);
        Rules.text("goal",fields.path("goal").asText(),2000,true);
        if (!fields.path("serviceMode").asText().equals("ONLINE") && fields.path("areaCode").asText("").isBlank())
            Rules.text("locationText",fields.path("locationText").asText(""),80,true);
        if (fields.path("categoryCode").asText().equals("EXTRACURRICULAR_STUDY")) {
            Rules.text("grade",fields.path("grade").asText(""),20,true);
            Rules.text("subject",fields.path("subject").asText(""),40,true);
        }
        if (!fields.has("frequency") || (fields.path("frequency").path("mode").asText().equals("PER_WEEK") && !fields.path("frequency").hasNonNull("count")))
            throw ApiError.invalid("frequency","请选择每周次数或可协商");
        if (fields.has("availabilityPreference")) {
            if (fields.path("availabilityPreference").path("mode").asText().equals("PREFERRED") && fields.path("availabilityPreference").path("weekdays").isEmpty())
                throw ApiError.invalid("availabilityPreference","请选择偏好的日期，或改为时间可协商");
        } else if (!fields.path("availableSlots").isArray() || fields.path("availableSlots").isEmpty())
            throw ApiError.invalid("availableSlots","请选择时间可协商、偏好时段或填写精确时间");
        if (fields.has("expectedStartDate") && LocalDate.parse(fields.get("expectedStartDate").asText()).isBefore(now.atZoneSameInstant(ZONE).toLocalDate()))
            throw ApiError.invalid("expectedStartDate","期望开始日期不能早于今天，请修改后重新保存");
        if (!"publisher-v1".equals(body.declarationVersion()) || !Set.of("SELF_ADULT","GUARDIAN_OF_MINOR").contains(body.publisherDeclaration()==null?"":body.publisherDeclaration()))
            throw ApiError.invalid("publisherDeclaration","请主动选择本次发布声明");
        String stage=fields.path("audienceStageCode").asText();
        if ((Set.of("PRIMARY","JUNIOR","SENIOR").contains(stage) && !body.publisherDeclaration().equals("GUARDIAN_OF_MINOR")) ||
            (stage.equals("ADULT") && !body.publisherDeclaration().equals("SELF_ADULT")))
            throw ApiError.invalid("publisherDeclaration","发布声明与服务对象阶段不一致，请重新核对");
    }
    private SubmissionRules() {}
}
