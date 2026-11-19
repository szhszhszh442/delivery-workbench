package io.projectforjob.businessapi.directory;

import static io.projectforjob.businessapi.api.RequestData.id;
import static io.projectforjob.businessapi.api.RequestData.requiredText;

import java.util.Locale;
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
import tools.jackson.databind.JsonNode;

import io.projectforjob.businessapi.api.ApiFailure;
import io.projectforjob.businessapi.api.ApiResponse;
import io.projectforjob.businessapi.api.Paging;

@RestController
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
class DirectoryController {

    private static final Set<String> SPACE_ROLES = Set.of("ADMIN", "MEMBER");
    private static final Set<String> PROJECT_ROLES = Set.of("OWNER", "CONTRIBUTOR", "OBSERVER");
    private final DirectoryService directory;

    DirectoryController(DirectoryService directory) {
        this.directory = directory;
    }

    @GetMapping("/workspaces")
    ApiResponse<?> workspaces(Authentication user, @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.success(directory.workspaces(user.getName(), new Paging(page, pageSize)));
    }

    @PostMapping(path = "/workspaces", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> createWorkspace(Authentication user, @RequestBody NewWorkspace body,
            @RequestHeader(name = "Idempotency-Key", required = false) String key) {
        return directory.createWorkspace(user.getName(), key, requiredText(body.name(), 120)).response();
    }

    @GetMapping("/workspaces/{id}/members")
    ApiResponse<?> workspaceMembers(Authentication user, @PathVariable String id,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.success(directory.workspaceMembers(user.getName(), id(id), new Paging(page, pageSize)));
    }

    @PostMapping(path = "/workspaces/{id}/members", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> addWorkspaceMember(Authentication user, @PathVariable String id,
            @RequestBody AddWorkspaceMember body,
            @RequestHeader(name = "Idempotency-Key", required = false) String key) {
        var login = requiredText(body.loginName(), 64).toLowerCase(Locale.ROOT);
        return directory.addWorkspaceMember(user.getName(), id(id), key, login, role(body.role(), SPACE_ROLES)).response();
    }

    @GetMapping("/workspaces/{id}/projects")
    ApiResponse<?> projects(Authentication user, @PathVariable String id,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.success(directory.projects(user.getName(), id(id), new Paging(page, pageSize)));
    }

    @PostMapping(path = "/workspaces/{id}/projects", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> createProject(Authentication user, @PathVariable String id, @RequestBody NewProject body,
            @RequestHeader(name = "Idempotency-Key", required = false) String key) {
        return directory.createProject(user.getName(), id(id), key,
                requiredText(body.name(), 120), requiredText(body.goal(), 60_000)).response();
    }

    @GetMapping("/projects/{id}")
    ApiResponse<?> project(Authentication user, @PathVariable String id) {
        return ApiResponse.success(directory.project(user.getName(), id(id)));
    }

    @GetMapping("/projects/{id}/members")
    ApiResponse<?> projectMembers(Authentication user, @PathVariable String id,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.success(directory.projectMembers(user.getName(), id(id), new Paging(page, pageSize)));
    }

    @PostMapping(path = "/projects/{id}/members", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> addProjectMember(Authentication user, @PathVariable String id, @RequestBody AddProjectMember body,
            @RequestHeader(name = "Idempotency-Key", required = false) String key) {
        return directory.addProjectMember(user.getName(), id(id), key,
                id(requiredText(body.workspaceMemberId(), 19)), role(body.role(), PROJECT_ROLES)).response();
    }

    @GetMapping("/commands/{key}")
    ApiResponse<?> receipt(Authentication user, @PathVariable String key) {
        return ApiResponse.success(directory.receipt(user.getName(), key));
    }

    private String role(JsonNode node, Set<String> roles) {
        var value = requiredText(node, 16);
        if (!roles.contains(value)) {
            throw ApiFailure.invalid();
        }
        return value;
    }

    record NewWorkspace(JsonNode name) {
    }

    record AddWorkspaceMember(JsonNode loginName, JsonNode role) {
    }

    record NewProject(JsonNode name, JsonNode goal) {
    }

    record AddProjectMember(JsonNode workspaceMemberId, JsonNode role) {
    }

}
