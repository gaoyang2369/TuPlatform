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
        for (String name : Set.of("title","categoryCode","audienceStageCode","areaCode","goal","budgetMin","budgetMax","durationHours")) {
            if (!fields.hasNonNull(name) || fields.path(name).asText().isBlank()) throw ApiError.invalid(name,"提交前请补全此项");
        }
        Rules.text("title",fields.path("title").asText(),60,true);
        Rules.text("goal",fields.path("goal").asText(),2000,true);
        if (!fields.has("frequency") || (fields.path("frequency").path("mode").asText().equals("PER_WEEK") && !fields.path("frequency").hasNonNull("count")))
            throw ApiError.invalid("frequency","请选择每周次数或可协商");
        if (!fields.path("availableSlots").isArray() || fields.path("availableSlots").isEmpty())
            throw ApiError.invalid("availableSlots","提交前请至少填写一项时段");
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
