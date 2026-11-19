package io.projectforjob.businessapi.document;

import static io.projectforjob.businessapi.api.RequestData.fields;
import static io.projectforjob.businessapi.api.RequestData.id;
import static io.projectforjob.businessapi.api.RequestData.optionalText;
import static io.projectforjob.businessapi.api.RequestData.requiredText;
import static io.projectforjob.businessapi.api.RequestData.version;

import java.util.Set;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.ObjectNode;

import io.projectforjob.businessapi.api.ApiFailure;
import io.projectforjob.businessapi.api.ApiResponse;
import io.projectforjob.businessapi.api.Paging;
import io.projectforjob.businessapi.document.DocumentData.Content;

@RestController
@RequestMapping(path = "/api/v1/projects/{projectId}/documents", produces = MediaType.APPLICATION_JSON_VALUE)
class DocumentController {

    private static final Set<String> KINDS = Set.of("REQUIREMENT", "PLAN", "DECISION", "DELIVERY", "HANDOVER", "GENERAL");
    private final DocumentService documents;

    DocumentController(DocumentService documents) {
        this.documents = documents;
    }

    @GetMapping
    ApiResponse<?> list(Authentication user, @PathVariable String projectId,
            @RequestParam(required = false) String kind, @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
        if (q.length() > 200) {
            throw ApiFailure.invalid();
        }
        return ApiResponse.success(documents.list(user.getName(), id(projectId), kind == null ? null : kind(kind),
                q.strip(), new Paging(page, pageSize)));
    }

    @GetMapping("/{documentId}")
    ApiResponse<?> get(Authentication user, @PathVariable String projectId, @PathVariable String documentId) {
        return ApiResponse.success(documents.get(user.getName(), id(projectId), id(documentId)));
    }

    @GetMapping("/{documentId}/revisions")
    ApiResponse<?> history(Authentication user, @PathVariable String projectId, @PathVariable String documentId,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.success(documents.history(user.getName(), id(projectId), id(documentId), new Paging(page, pageSize)));
    }

    @GetMapping("/{documentId}/revisions/{revisionId}")
    ApiResponse<?> revision(Authentication user, @PathVariable String projectId, @PathVariable String documentId,
            @PathVariable String revisionId) {
        return ApiResponse.success(documents.getRevision(user.getName(), id(projectId), id(documentId), id(revisionId)));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> create(Authentication user, @PathVariable String projectId, @RequestBody ObjectNode body,
            @RequestHeader(name = "Idempotency-Key", required = false) String key) {
        fields(body, "kind", "title", "contentMarkdown", "changeNote");
        return documents.create(user.getName(), id(projectId), key, kind(requiredText(body.get("kind"), 16)),
                content(body)).response();
    }

    @PostMapping(path = "/{documentId}/revisions", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> append(Authentication user, @PathVariable String projectId, @PathVariable String documentId,
            @RequestBody ObjectNode body, @RequestHeader(name = "Idempotency-Key", required = false) String key) {
        fields(body, "expectedVersion", "baseRevisionId", "title", "contentMarkdown", "changeNote");
        if (!body.has("changeNote")) {
            throw ApiFailure.invalid();
        }
        return documents.append(user.getName(), id(projectId), id(documentId), key, version(body),
                id(requiredText(body.get("baseRevisionId"), 19)), content(body)).response();
    }

    private Content content(ObjectNode body) {
        var markdown = body.get("contentMarkdown");
        if (markdown == null || !markdown.isString() || markdown.asString().length() > 60_000
                || markdown.asString().isBlank()) {
            throw ApiFailure.invalid();
        }
        return new Content(requiredText(body.get("title"), 200), markdown.asString(), optionalText(body.get("changeNote"), 500));
    }

    private String kind(String value) {
        if (!KINDS.contains(value)) {
            throw ApiFailure.invalid();
        }
        return value;
    }
}
