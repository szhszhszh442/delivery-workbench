package io.projectforjob.businessapi.task;

import static java.time.ZoneOffset.UTC;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

final class SubmissionData {

    private SubmissionData() {
    }

    enum Action {
        APPROVE("APPROVED", "DONE", "SUBMISSION_APPROVED"),
        RETURN("RETURNED", "IN_PROGRESS", "SUBMISSION_RETURNED"),
        WITHDRAW("WITHDRAWN", "IN_PROGRESS", "SUBMISSION_WITHDRAWN");

        final String submissionStatus;
        final String taskStatus;
        final String event;

        Action(String submissionStatus, String taskStatus, String event) {
            this.submissionStatus = submissionStatus;
            this.taskStatus = taskStatus;
            this.event = event;
        }

        String path() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    record MaterialInput(String kind, String label, String body, String url,
            @JsonInclude(JsonInclude.Include.NON_NULL) Long documentRevisionId) {
    }

    record Input(String summary, List<MaterialInput> materials) {
    }

    record Row(long id, long taskId, int submissionNo, long submittedByMemberId, long assigneeSnapshotMemberId,
            long reviewerSnapshotMemberId, int taskVersionAtSubmit, String scopeSnapshot, String summary,
            String status, LocalDateTime endedAt, Long endedByMemberId, String endReason, LocalDateTime createdAt) {
        Detail detail(JsonNode scope, List<Material> materials, Decision decision) {
            return new Detail(Long.toString(id), Long.toString(taskId), submissionNo, status, summary,
                    Long.toString(submittedByMemberId), Long.toString(assigneeSnapshotMemberId),
                    Long.toString(reviewerSnapshotMemberId), taskVersionAtSubmit, scope, materials, decision,
                    endedAt == null ? null : endedAt.toInstant(UTC), TaskData.stringId(endedByMemberId), endReason,
                    createdAt.toInstant(UTC));
        }
    }

    record SummaryRow(long id, long taskId, int submissionNo, String status, String summary, LocalDateTime createdAt) {
        Summary view() {
            return new Summary(Long.toString(id), Long.toString(taskId), submissionNo, status, summary,
                    createdAt.toInstant(UTC));
        }
    }

    record Summary(String id, String taskId, int submissionNo, String status, String summary, Instant createdAt) {
    }

    record MaterialRow(long id, String kind, String label, String body, String url, int position,
            Long documentRevisionId, Long documentId, Integer revisionNo) {
        Material view() {
            return new Material(Long.toString(id), kind, label, body, url, position,
                    TaskData.stringId(documentRevisionId), TaskData.stringId(documentId), revisionNo);
        }
    }

    record Material(String id, String kind, String label, String body, String url, int position,
            @JsonInclude(JsonInclude.Include.NON_NULL) String documentRevisionId,
            @JsonInclude(JsonInclude.Include.NON_NULL) String documentId,
            @JsonInclude(JsonInclude.Include.NON_NULL) Integer revisionNo) {
    }

    record DecisionRow(long reviewerMemberId, String decision, String comment, LocalDateTime createdAt) {
        Decision view() {
            return new Decision(Long.toString(reviewerMemberId), decision, comment, createdAt.toInstant(UTC));
        }
    }

    record Decision(String reviewerMemberId, String decision, String comment, Instant createdAt) {
    }

    record Detail(String id, String taskId, int submissionNo, String status, String summary,
            String submittedByMemberId, String assigneeSnapshotMemberId, String reviewerSnapshotMemberId,
            int taskVersionAtSubmit, JsonNode scopeSnapshot, List<Material> materials, Decision decision,
            Instant endedAt, String endedByMemberId, String endReason, Instant createdAt) {
    }

    record Result(TaskData.Detail task, Detail submission, Decision decision) {
    }
}
