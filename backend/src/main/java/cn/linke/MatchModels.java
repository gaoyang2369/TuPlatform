package cn.linke;

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
    boolean submissionChanged, boolean teacherProfileChanged) {}
