package cn.linke;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import java.util.UUID;

record ContactExchange(Boolean agree, Long expectedVersion) {}
record StartMatch(UUID requestId, UUID expectedSubmissionId, String kind, UUID tutorUserId, Long expectedTeacherProfileVersion, @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String introduction,
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) ContactExchange contactExchange) {
    StartMatch(UUID requestId, UUID expectedSubmissionId, String kind, UUID tutorUserId, Long expectedTeacherProfileVersion, String introduction) {
        this(requestId,expectedSubmissionId,kind,tutorUserId,expectedTeacherProfileVersion,introduction,null);
    }
    StartMatch(UUID requestId, UUID expectedSubmissionId, String kind, UUID tutorUserId, Long expectedTeacherProfileVersion) {
        this(requestId,expectedSubmissionId,kind,tutorUserId,expectedTeacherProfileVersion,null);
    }
}
record RespondMatch(UUID roundId, Long expectedVersion,
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) ContactExchange contactExchange) {
    RespondMatch(UUID roundId,Long expectedVersion) { this(roundId,expectedVersion,null); }
}
record MatchView(UUID id, UUID requestId, long version, UUID roundId, String state, String initiatorRole,
    String myRole, boolean waitingForMe, boolean requestExpired, boolean teacherPublic,
    boolean canAccept, boolean canReject, boolean canWithdraw, boolean canInitiateAgain,
    Map<String,Object> submissionSnapshot, Map<String,Object> teacherSnapshot,
    boolean submissionChanged, boolean teacherProfileChanged, @JsonProperty("avatar_url") String avatarUrl) {
    MatchView {
        // Applies to stored rounds and historical idempotency receipts as well as fresh projections.
        submissionSnapshot = new java.util.LinkedHashMap<>(submissionSnapshot);
        submissionSnapshot.remove("locationText");
        submissionSnapshot.remove("exact_address");
        Object oldStreet = submissionSnapshot.remove("areaCode");
        if (oldStreet instanceof String code && RequestLocation.STREETS.containsKey(code))
            submissionSnapshot.putIfAbsent("street", RequestLocation.STREETS.get(code));
    }
}

record MatchDetail(@com.fasterxml.jackson.annotation.JsonUnwrapped MatchView match,
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String exact_address) {}
