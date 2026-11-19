package io.projectforjob.businessapi.document;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import io.projectforjob.businessapi.api.ApiFailure;
import io.projectforjob.businessapi.api.InsertId;
import io.projectforjob.businessapi.api.Paging;
import io.projectforjob.businessapi.api.Paging.Page;
import io.projectforjob.businessapi.directory.DirectoryCommands;
import io.projectforjob.businessapi.directory.DirectoryCommands.Reply;
import io.projectforjob.businessapi.directory.DirectoryCommands.Saved;
import io.projectforjob.businessapi.directory.DirectoryService;
import io.projectforjob.businessapi.directory.DirectoryService.ProjectAccess;
import io.projectforjob.businessapi.document.DocumentData.Content;
import io.projectforjob.businessapi.document.DocumentData.Detail;
import io.projectforjob.businessapi.document.DocumentData.DocumentRow;
import io.projectforjob.businessapi.document.DocumentData.Revision;
import io.projectforjob.businessapi.document.DocumentData.RevisionSummary;
import io.projectforjob.businessapi.document.DocumentData.Summary;

@Service
public class DocumentService {

    private final DocumentMapper documents;
    private final DirectoryService directory;
    private final DirectoryCommands commands;
    private final ObjectMapper json;

    DocumentService(DocumentMapper documents, DirectoryService directory, DirectoryCommands commands, ObjectMapper json) {
        this.documents = documents;
        this.directory = directory;
        this.commands = commands;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public Page<Summary> list(String login, long projectId, String kind, String query, Paging paging) {
        directory.projectAccess(directory.user(login), projectId);
        var items = documents.list(projectId, kind, query, paging.pageSize(), paging.offset()).stream()
                .map(DocumentData.DocumentRow::summary).toList();
        return paging.result(items, documents.count(projectId, kind, query));
    }

    @Transactional(readOnly = true)
    public Detail get(String login, long projectId, long documentId) {
        directory.projectAccess(directory.user(login), projectId);
        return detail(find(projectId, documentId));
    }

    @Transactional(readOnly = true)
    public Page<RevisionSummary> history(String login, long projectId, long documentId, Paging paging) {
        directory.projectAccess(directory.user(login), projectId);
        find(projectId, documentId);
        var items = documents.history(projectId, documentId, paging.pageSize(), paging.offset()).stream()
                .map(DocumentData.RevisionInfoRow::view).toList();
        return paging.result(items, documents.countRevisions(projectId, documentId));
    }

    @Transactional(readOnly = true)
    public Revision getRevision(String login, long projectId, long documentId, long revisionId) {
        directory.projectAccess(directory.user(login), projectId);
        var revision = documents.revision(projectId, documentId, revisionId);
        if (revision == null) {
            throw ApiFailure.notFound();
        }
        return revision.view();
    }

    public boolean hasRevision(long projectId, long revisionId) {
        return documents.hasRevision(projectId, revisionId);
    }

    Reply create(String login, long projectId, String key, String kind, Content content) {
        var actor = directory.user(login);
        return commands.execute(actor, key, path(projectId), List.of(kind, content),
                () -> directory.lockProjectAccess(actor, projectId), access -> {
                    requireWrite(access);
                    var id = new InsertId();
                    documents.create(projectId, kind, content.title(), access.memberId(), id);
                    return save(actor, access, find(projectId, id.getId()), content, 1, 201, "DOCUMENT_CREATED");
                });
    }

    Reply append(String login, long projectId, long documentId, String key, int version, long baseRevisionId,
            Content content) {
        var actor = directory.user(login);
        return commands.execute(actor, key, path(projectId) + "/" + documentId + "/revisions",
                List.of(version, baseRevisionId, content), () -> directory.lockProjectAccess(actor, projectId),
                access -> {
                    requireWrite(access);
                    var row = find(projectId, documentId);
                    if (row.version() != version || !Objects.equals(row.currentRevisionId(), baseRevisionId)) {
                        throw versionConflict(row);
                    }
                    return save(actor, access, row, content, version + 1, 201, "DOCUMENT_REVISION_CREATED");
                });
    }

    private Saved save(long actor, ProjectAccess access, DocumentRow row, Content content, int nextVersion, int status,
            String event) {
        var next = new InsertId();
        var sequence = row.revisionSeq() + 1;
        documents.append(row.projectId(), row.id(), sequence, content, hash(content.contentMarkdown()), access.memberId(), next);
        if (documents.advance(row.projectId(), row.id(), row.currentRevisionId(), row.version(), next.getId(),
                sequence, content.title(), nextVersion) != 1) {
            throw versionConflict(find(row.projectId(), row.id()));
        }
        var saved = detail(find(row.projectId(), row.id()));
        var activity = json.createObjectNode().put("revisionId", saved.currentRevision().id())
                .put("revisionNo", sequence).put("version", nextVersion);
        return new Saved(access.workspaceId(), row.projectId(), status, saved, commandId ->
                documents.recordActivity(row.projectId(), actor, access.memberId(), event, row.id(), commandId,
                        json.writeValueAsString(activity)));
    }

    private DocumentRow find(long projectId, long documentId) {
        var row = documents.find(projectId, documentId);
        if (row == null) {
            throw ApiFailure.notFound();
        }
        return row;
    }

    private Detail detail(DocumentRow row) {
        return row.detail(documents.revision(row.projectId(), row.id(), row.currentRevisionId()));
    }

    private void requireWrite(ProjectAccess access) {
        if (!access.canWork()) {
            throw ApiFailure.forbidden();
        }
        if (!access.active()) {
            throw ApiFailure.conflict("项目已归档");
        }
    }

    private ApiFailure versionConflict(DocumentRow row) {
        return ApiFailure.versionConflict("DOCUMENT", row.id(), row.version(), "文档已有新版本，请核对后再保存");
    }

    private String path(long projectId) {
        return "POST /api/v1/projects/" + projectId + "/documents";
    }

    private String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
