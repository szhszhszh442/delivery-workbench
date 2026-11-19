package io.projectforjob.businessapi.document;

import static java.time.ZoneOffset.UTC;

import java.time.Instant;
import java.time.LocalDateTime;

final class DocumentData {

    private DocumentData() {
    }

    record Content(String title, String contentMarkdown, String changeNote) {
    }

    record DocumentRow(long id, long projectId, String kind, String title, Long currentRevisionId,
            int revisionSeq, int version, LocalDateTime updatedAt) {
        Summary summary() {
            return new Summary(Long.toString(id), Long.toString(projectId), kind, title,
                    Long.toString(currentRevisionId), revisionSeq, version, updatedAt.toInstant(UTC));
        }

        Detail detail(RevisionRow revision) {
            return new Detail(Long.toString(id), Long.toString(projectId), kind, title, version, revision.view());
        }
    }

    record RevisionInfoRow(long id, long documentId, int revisionNo, String title, long editorMemberId,
            String changeNote, LocalDateTime createdAt) {
        RevisionSummary view() {
            return new RevisionSummary(Long.toString(id), Long.toString(documentId), revisionNo, title,
                    Long.toString(editorMemberId), changeNote, createdAt.toInstant(UTC));
        }
    }

    record RevisionRow(long id, long documentId, int revisionNo, String title, String contentMarkdown,
            long editorMemberId, String changeNote, LocalDateTime createdAt) {
        Revision view() {
            return new Revision(Long.toString(id), Long.toString(documentId), revisionNo, title, contentMarkdown,
                    Long.toString(editorMemberId), changeNote, createdAt.toInstant(UTC));
        }
    }

    record Summary(String id, String projectId, String kind, String title, String currentRevisionId,
            int revisionNo, int version, Instant updatedAt) {
    }

    record Detail(String id, String projectId, String kind, String title, int version, Revision currentRevision) {
    }

    record RevisionSummary(String id, String documentId, int revisionNo, String title,
            String editorMemberId, String changeNote, Instant createdAt) {
    }

    record Revision(String id, String documentId, int revisionNo, String title, String contentMarkdown,
            String editorMemberId, String changeNote, Instant createdAt) {
    }
}
