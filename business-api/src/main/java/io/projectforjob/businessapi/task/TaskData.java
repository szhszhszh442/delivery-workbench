package io.projectforjob.businessapi.task;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

final class TaskData {

    private TaskData() {
    }

    static String stringId(Long id) {
        return id == null ? null : Long.toString(id);
    }

    record Entry(String key, String text) {
    }

    record Scope(String title, String goal, String expectedOutcome,
            List<Entry> acceptanceCriteria, List<Entry> openQuestions) {
        Scope apply(Scope changes) {
            return new Scope(
                    changes.title == null ? title : changes.title,
                    changes.goal == null ? goal : changes.goal,
                    changes.expectedOutcome == null ? expectedOutcome : changes.expectedOutcome,
                    changes.acceptanceCriteria == null ? acceptanceCriteria : changes.acceptanceCriteria,
                    changes.openQuestions == null ? openQuestions : changes.openQuestions);
        }
    }

    record Filters(String status, Long assigneeMemberId, Long reviewerMemberId, Long parentTaskId, String q) {
    }

    record Assignment(Long assigneeMemberId, Long reviewerMemberId, String reason) {
    }

    record Row(long id, long projectId, Long parentTaskId, String title, String goal, String expectedOutcome,
            String acceptanceCriteria, String openQuestions, String executionNotes, String status,
            long creatorMemberId, Long assigneeMemberId, Long reviewerMemberId, Long currentSubmissionId,
            int submissionSeq, int version, LocalDateTime createdAt, LocalDateTime updatedAt) {
        Detail detail(Scope scope, List<String> allowedActions) {
            return new Detail(Long.toString(id), Long.toString(projectId), stringId(parentTaskId), title,
                    goal, expectedOutcome, scope.acceptanceCriteria(), scope.openQuestions(), executionNotes,
                    status, Long.toString(creatorMemberId), stringId(assigneeMemberId), stringId(reviewerMemberId),
                    stringId(currentSubmissionId), version, allowedActions, createdAt.toInstant(ZoneOffset.UTC),
                    updatedAt.toInstant(ZoneOffset.UTC));
        }
    }

    record SummaryRow(long id, long projectId, String title, String status, long creatorMemberId,
            Long assigneeMemberId, Long reviewerMemberId, int version) {
        Summary view() {
            return new Summary(Long.toString(id), Long.toString(projectId), title, status,
                    Long.toString(creatorMemberId), stringId(assigneeMemberId), stringId(reviewerMemberId), version);
        }
    }

    record Summary(String id, String projectId, String title, String status, String creatorMemberId,
            String assigneeMemberId, String reviewerMemberId, int version) {
    }

    record Detail(String id, String projectId, String parentTaskId, String title, String goal, String expectedOutcome,
            List<Entry> acceptanceCriteria, List<Entry> openQuestions, String executionNotes, String status,
            String creatorMemberId, String assigneeMemberId, String reviewerMemberId, String currentSubmissionId,
            int version, List<String> allowedActions, Instant createdAt, Instant updatedAt) {
    }
}
