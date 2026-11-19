package io.projectforjob.businessapi.directory;

import static io.projectforjob.businessapi.api.RequestData.fields;
import static io.projectforjob.businessapi.api.RequestData.id;
import static io.projectforjob.businessapi.api.RequestData.requiredText;
import static io.projectforjob.businessapi.api.RequestData.version;

import java.util.Set;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.ObjectNode;

import io.projectforjob.businessapi.api.ApiFailure;
import io.projectforjob.businessapi.api.ApiResponse;
import io.projectforjob.businessapi.directory.MembershipService.RemovalMode;

@RestController
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
class MembershipController {

    private static final Set<String> SPACE_ROLES = Set.of("ADMIN", "MEMBER");
    private static final Set<String> PROJECT_ROLES = Set.of("OWNER", "CONTRIBUTOR", "OBSERVER");
    private final MembershipService memberships;

    MembershipController(MembershipService memberships) {
        this.memberships = memberships;
    }

    @GetMapping("/projects/{projectId}/members/{memberId}/responsibilities")
    ApiResponse<?> responsibilities(Authentication user, @PathVariable String projectId, @PathVariable String memberId) {
        return ApiResponse.success(memberships.responsibilities(user.getName(), id(projectId), id(memberId)));
    }

    @PatchMapping(path = "/projects/{projectId}/members/{memberId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> projectRole(Authentication user, @PathVariable String projectId, @PathVariable String memberId,
            @RequestBody ObjectNode body, @RequestHeader(name = "Idempotency-Key", required = false) String key) {
        fields(body, "expectedVersion", "role", "reason");
        return memberships.changeProjectRole(user.getName(), id(projectId), id(memberId), key, version(body),
                role(body, PROJECT_ROLES), reason(body)).response();
    }

    @PostMapping(path = "/projects/{projectId}/members/{memberId}/remove", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> removeProjectMember(Authentication user, @PathVariable String projectId, @PathVariable String memberId,
            @RequestBody ObjectNode body, @RequestHeader(name = "Idempotency-Key", required = false) String key) {
        fields(body, "expectedVersion", "reason", "mode");
        return memberships.removeProjectMember(user.getName(), id(projectId), id(memberId), key,
                version(body), reason(body), mode(body, false)).response();
    }

    @PatchMapping(path = "/workspaces/{workspaceId}/members/{memberId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> workspaceRole(Authentication user, @PathVariable String workspaceId, @PathVariable String memberId,
            @RequestBody ObjectNode body, @RequestHeader(name = "Idempotency-Key", required = false) String key) {
        fields(body, "expectedVersion", "role");
        return memberships.changeWorkspaceRole(user.getName(), id(workspaceId), id(memberId), key,
                version(body), role(body, SPACE_ROLES)).response();
    }

    @PostMapping(path = "/workspaces/{workspaceId}/members/{memberId}/remove", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> removeWorkspaceMember(Authentication user, @PathVariable String workspaceId, @PathVariable String memberId,
            @RequestBody ObjectNode body, @RequestHeader(name = "Idempotency-Key", required = false) String key) {
        fields(body, "expectedVersion", "reason", "mode");
        return memberships.removeWorkspaceMember(user.getName(), id(workspaceId), id(memberId), key,
                version(body), reason(body), mode(body, true)).response();
    }

    private String role(ObjectNode body, Set<String> roles) {
        var role = requiredText(body.get("role"), 16);
        if (!roles.contains(role)) {
            throw ApiFailure.invalid();
        }
        return role;
    }

    private String reason(ObjectNode body) {
        return requiredText(body.get("reason"), 60_000);
    }

    private RemovalMode mode(ObjectNode body, boolean useDefault) {
        if (!body.has("mode") && useDefault) {
            return RemovalMode.REQUIRE_CLEAR;
        }
        return switch (requiredText(body.get("mode"), 16)) {
            case "REQUIRE_CLEAR" -> RemovalMode.REQUIRE_CLEAR;
            case "REVOKE_NOW" -> RemovalMode.REVOKE_NOW;
            default -> throw ApiFailure.invalid();
        };
    }
}
