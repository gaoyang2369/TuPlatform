package cn.linke;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;
import java.util.UUID;

record SubmitRequest(Long expectedVersion, String publisherDeclaration, String declarationVersion) {}
record ReviewRequest(Long expectedVersion, String decision, String reason) {}
record RequestAction(UUID id, UUID submissionId, String status, long version) {}
record SubmissionSnapshot(UUID id, ObjectNode fields, String publisherDisplayName, String publisherDeclaration,
                          String declarationVersion, OffsetDateTime submittedAt, String reviewFeedback) {}
record ReviewSubmission(UUID id, UUID requestId, String status, long version, OffsetDateTime submittedAt,
                        OffsetDateTime expiresAt, ObjectNode fields, String publisherDisplayName,
                        String publisherDeclaration, String declarationVersion, String reviewFeedback, String priorReviewFeedback) {}
