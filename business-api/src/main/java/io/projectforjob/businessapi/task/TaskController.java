package io.projectforjob.businessapi.task;

import static io.projectforjob.businessapi.api.RequestData.fields;
import static io.projectforjob.businessapi.api.RequestData.id;
import static io.projectforjob.businessapi.api.RequestData.optionalText;
import static io.projectforjob.businessapi.api.RequestData.requiredText;
import static io.projectforjob.businessapi.api.RequestData.text;
import static io.projectforjob.businessapi.api.RequestData.version;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.projectforjob.businessapi.api.ApiFailure;
import io.projectforjob.businessapi.api.ApiResponse;
import io.projectforjob.businessapi.api.Paging;
import io.projectforjob.businessapi.task.TaskData.Assignment;
import io.projectforjob.businessapi.task.TaskData.Entry;
import io.projectforjob.businessapi.task.TaskData.Filters;
import io.projectforjob.businessapi.task.TaskData.Scope;

@RestController
@RequestMapping(path = "/api/v1/projects/{projectId}/tasks", produces = MediaType.APPLICATION_JSON_VALUE)
class TaskController {

    private static final Set<String> STATUSES = Set.of("DRAFT", "READY", "IN_PROGRESS", "IN_REVIEW", "DONE", "CANCELLED");
    private static final int BODY_TEXT_LIMIT = 60_000;
    private final TaskService tasks;

    TaskController(TaskService tasks) {
        this.tasks = tasks;
    }

    @GetMapping
    ApiResponse<?> list(Authentication user, @PathVariable String projectId,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String status, @RequestParam(required = false) String assigneeMemberId,
            @RequestParam(required = false) String reviewerMemberId, @RequestParam(required = false) String parentTaskId,
            @RequestParam(required = false) String q) {
        if ((status != null && !STATUSES.contains(status)) || (q != null && q.length() > 200)) {
            throw ApiFailure.invalid();
        }
        var filters = new Filters(status, optionalId(assigneeMemberId), optionalId(reviewerMemberId),
                optionalId(parentTaskId), q == null || q.isBlank() ? null : q.strip());
        return ApiResponse.success(tasks.list(user.getName(), id(projectId), new Paging(page, pageSize), filters));
    }

    @GetMapping("/{taskId}")
    ApiResponse<?> get(Authentication user, @PathVariable String projectId, @PathVariable String taskId) {
        return ApiResponse.success(tasks.get(user.getName(), id(projectId), id(taskId)));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> create(Authentication user, @PathVariable String projectId, @RequestBody ObjectNode body,
            @RequestHeader(name = "Idempotency-Key", required = false) String key) {
        fields(body, "title", "goal", "expectedOutcome", "acceptanceCriteria", "openQuestions", "parentTaskId",
                "expectedParentVersion");
        if (body.hasNonNull("parentTaskId") || body.hasNonNull("expectedParentVersion")) {
            throw ApiFailure.invalidRelation("当前阶段支持创建顶层任务");
        }
        var scope = new Scope(requiredText(body.get("title"), 200), optionalText(body.get("goal"), BODY_TEXT_LIMIT),
                optionalText(body.get("expectedOutcome"), BODY_TEXT_LIMIT), entries(body.get("acceptanceCriteria")),
                entries(body.get("openQuestions")));
        return tasks.create(user.getName(), id(projectId), key, scope).response();
    }

    @PatchMapping(path = "/{taskId}/scope", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> editScope(Authentication user, @PathVariable String projectId, @PathVariable String taskId,
            @RequestBody ObjectNode body, @RequestHeader(name = "Idempotency-Key", required = false) String key) {
        fields(body, "expectedVersion", "title", "goal", "expectedOutcome", "acceptanceCriteria", "openQuestions", "reason");
        if (!body.has("title") && !body.has("goal") && !body.has("expectedOutcome")
                && !body.has("acceptanceCriteria") && !body.has("openQuestions")) {
            throw ApiFailure.invalid();
        }
        var changes = new Scope(body.has("title") ? requiredText(body.get("title"), 200) : null,
                body.has("goal") ? text(body.get("goal"), BODY_TEXT_LIMIT) : null,
                body.has("expectedOutcome") ? text(body.get("expectedOutcome"), BODY_TEXT_LIMIT) : null,
                body.has("acceptanceCriteria") ? entries(body.get("acceptanceCriteria")) : null,
                body.has("openQuestions") ? entries(body.get("openQuestions")) : null);
        return tasks.updateScope(user.getName(), id(projectId), id(taskId), key, version(body), changes,
                optionalText(body.get("reason"), BODY_TEXT_LIMIT)).response();
    }

    @PostMapping(path = "/{taskId}/ready", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> ready(Authentication user, @PathVariable String projectId, @PathVariable String taskId,
            @RequestBody ObjectNode body, @RequestHeader(name = "Idempotency-Key", required = false) String key) {
        fields(body, "expectedVersion");
        return tasks.ready(user.getName(), id(projectId), id(taskId), key, version(body)).response();
    }

    @PostMapping(path = "/{taskId}/claim", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> claim(Authentication user, @PathVariable String projectId, @PathVariable String taskId,
            @RequestBody ObjectNode body, @RequestHeader(name = "Idempotency-Key", required = false) String key) {
        fields(body, "expectedVersion");
        return tasks.claim(user.getName(), id(projectId), id(taskId), key, version(body)).response();
    }

    @PostMapping(path = "/{taskId}/start", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> start(Authentication user, @PathVariable String projectId, @PathVariable String taskId,
            @RequestBody ObjectNode body, @RequestHeader(name = "Idempotency-Key", required = false) String key) {
        fields(body, "expectedVersion");
        return tasks.start(user.getName(), id(projectId), id(taskId), key, version(body)).response();
    }

    @PutMapping(path = "/{taskId}/assignment", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> assign(Authentication user, @PathVariable String projectId, @PathVariable String taskId,
            @RequestBody ObjectNode body, @RequestHeader(name = "Idempotency-Key", required = false) String key) {
        fields(body, "expectedVersion", "assigneeMemberId", "reviewerMemberId", "reason");
        if (!body.has("assigneeMemberId") || !body.has("reviewerMemberId")) {
            throw ApiFailure.invalid();
        }
        var assignee = body.get("assigneeMemberId");
        var reviewer = body.get("reviewerMemberId");
        var assignment = new Assignment(assignee.isNull() ? null : id(requiredText(assignee, 19)),
                reviewer.isNull() ? null : id(requiredText(reviewer, 19)), optionalText(body.get("reason"), BODY_TEXT_LIMIT));
        return tasks.assign(user.getName(), id(projectId), id(taskId), key, version(body), assignment).response();
    }

    private Long optionalId(String value) {
        return value == null ? null : id(value);
    }

    private List<Entry> entries(JsonNode node) {
        if (node == null) {
            return List.of();
        }
        if (!node.isArray() || node.size() > 20) {
            throw ApiFailure.invalid();
        }
        var keys = new HashSet<String>();
        var result = new ArrayList<Entry>();
        for (var item : node) {
            if (!(item instanceof ObjectNode entry)) {
                throw ApiFailure.invalid();
            }
            fields(entry, "key", "text");
            var key = requiredText(entry.get("key"), 64);
            if (!keys.add(key)) {
                throw ApiFailure.invalid();
            }
            result.add(new Entry(key, requiredText(entry.get("text"), BODY_TEXT_LIMIT)));
        }
        return List.copyOf(result);
    }
}
