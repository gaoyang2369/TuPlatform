package cn.linke;

import java.util.Map;
import java.util.UUID;

record StartMatch(UUID requestId, UUID expectedSubmissionId, String kind, UUID tutorUserId, Long expectedTeacherProfileVersion) {}
record RespondMatch(UUID roundId, Long expectedVersion) {}
record MatchView(UUID id, UUID requestId, long version, UUID roundId, String state, String initiatorRole,
    String myRole, boolean waitingForMe, boolean requestExpired, boolean teacherPublic,
    boolean canAccept, boolean canReject, boolean canWithdraw, boolean canInitiateAgain,
    Map<String,Object> submissionSnapshot, Map<String,Object> teacherSnapshot,
    boolean submissionChanged, boolean teacherProfileChanged) {}
