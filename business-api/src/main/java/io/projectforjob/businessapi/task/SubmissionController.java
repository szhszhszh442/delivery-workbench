package io.projectforjob.businessapi.task;

import static io.projectforjob.businessapi.api.RequestData.fields;
import static io.projectforjob.businessapi.api.RequestData.id;
import static io.projectforjob.businessapi.api.RequestData.optionalText;
import static io.projectforjob.businessapi.api.RequestData.requiredText;
import static io.projectforjob.businessapi.api.RequestData.version;

import java.net.URI;
import java.util.ArrayList;
import java.util.Locale;

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
import io.projectforjob.businessapi.task.SubmissionData.Action;
import io.projectforjob.businessapi.task.SubmissionData.Input;
import io.projectforjob.businessapi.task.SubmissionData.MaterialInput;

@RestController
@RequestMapping(path = "/api/v1/projects/{projectId}/tasks/{taskId}/submissions", produces = MediaType.APPLICATION_JSON_VALUE)
class SubmissionController {

    private static final int BODY_TEXT_LIMIT = 60_000;
    private final SubmissionService submissions;

    SubmissionController(SubmissionService submissions) {
        this.submissions = submissions;
    }

    @GetMapping
    ApiResponse<?> list(Authentication user, @PathVariable String projectId, @PathVariable String taskId,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.success(submissions.list(user.getName(), id(projectId), id(taskId), new Paging(page, pageSize)));
    }

    @GetMapping("/{submissionId}")
    ApiResponse<?> get(Authentication user, @PathVariable String projectId, @PathVariable String taskId,
            @PathVariable String submissionId) {
        return ApiResponse.success(submissions.get(user.getName(), id(projectId), id(taskId), id(submissionId)));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> submit(Authentication user, @PathVariable String projectId, @PathVariable String taskId,
            @RequestBody ObjectNode body, @RequestHeader(name = "Idempotency-Key", required = false) String key) {
        fields(body, "expectedVersion", "summary", "materials");
        var materials = body.get("materials");
        if (materials == null || !materials.isArray() || materials.size() > 20) {
            throw ApiFailure.invalid();
        }
        if (materials.isEmpty()) {
            throw SubmissionService.violation("至少提供一项成果材料", "MATERIAL_REQUIRED");
        }
        var parsed = new ArrayList<MaterialInput>();
        for (var item : materials) {
            if (!(item instanceof ObjectNode material)) {
                throw ApiFailure.invalid();
            }
            parsed.add(material(material));
        }
        return submissions.submit(user.getName(), id(projectId), id(taskId), key, version(body),
                new Input(requiredText(body.get("summary"), BODY_TEXT_LIMIT), parsed)).response();
    }

    @PostMapping(path = "/{submissionId}/{action:approve|return|withdraw}", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> finish(Authentication user, @PathVariable String projectId, @PathVariable String taskId,
            @PathVariable String submissionId, @PathVariable String action, @RequestBody ObjectNode body,
            @RequestHeader(name = "Idempotency-Key", required = false) String key) {
        var decision = Action.valueOf(action.toUpperCase(Locale.ROOT));
        String note;
        if (decision == Action.WITHDRAW) {
            fields(body, "expectedVersion", "reason");
            note = requiredText(body.get("reason"), BODY_TEXT_LIMIT);
        } else {
            fields(body, "expectedVersion", "comment");
            if (decision == Action.RETURN && !body.has("comment")) {
                throw ApiFailure.invalid();
            }
            note = optionalText(body.get("comment"), BODY_TEXT_LIMIT);
            if (decision == Action.RETURN && note.isEmpty()) {
                throw SubmissionService.violation("请填写退回意见", "COMMENT_REQUIRED");
            }
        }
        return submissions.finish(user.getName(), id(projectId), id(taskId), id(submissionId), key, version(body),
                decision, note).response();
    }

    private MaterialInput material(ObjectNode item) {
        var kind = requiredText(item.get("kind"), 16);
        var label = requiredText(item.get("label"), 200);
        return switch (kind) {
            case "TEXT" -> {
                fields(item, "kind", "label", "body");
                yield new MaterialInput(kind, label, requiredText(item.get("body"), BODY_TEXT_LIMIT), null, null);
            }
            case "LINK" -> {
                fields(item, "kind", "label", "url");
                var value = requiredText(item.get("url"), 2048);
                if (!validLink(value)) {
                    throw SubmissionService.violation("请输入有效的 HTTP 或 HTTPS 链接", "MATERIAL_INVALID");
                }
                yield new MaterialInput(kind, label, null, value, null);
            }
            case "DOCUMENT" -> {
                fields(item, "kind", "label", "documentRevisionId");
                yield new MaterialInput(kind, label, null, null, id(requiredText(item.get("documentRevisionId"), 19)));
            }
            default -> throw ApiFailure.invalid();
        };
    }

    private boolean validLink(String value) {
        try {
            var uri = URI.create(value);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }
}
