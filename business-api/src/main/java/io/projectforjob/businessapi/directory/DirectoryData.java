package io.projectforjob.businessapi.directory;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

final class DirectoryData {

    private DirectoryData() {
    }

    record WorkspaceRow(long id, String name, int version, String currentRole) {
        Workspace view() {
            return new Workspace(Long.toString(id), name, version, currentRole);
        }
    }

    record Workspace(String id, String name, int version, String currentRole) {
    }

    record ProjectRow(long id, long workspaceId, long memberId, String name, String goal, String status, String currentRole,
            int version, LocalDateTime createdAt, LocalDateTime updatedAt) {
        Project view() {
            return new Project(Long.toString(id), Long.toString(workspaceId), name, goal, status, currentRole,
                    version, createdAt.toInstant(ZoneOffset.UTC), updatedAt.toInstant(ZoneOffset.UTC));
        }
    }

    record Project(String id, String workspaceId, String name, String goal, String status, String currentRole,
            int version, Instant createdAt, Instant updatedAt) {
    }

    record SpaceMemberRow(long id, long userId, String loginName, String displayName, String accountStatus,
            String role, String status, int version) {
        WorkspaceMember view() {
            return new WorkspaceMember(Long.toString(id),
                    new WorkspaceUser(Long.toString(userId), loginName, displayName), role, status, version);
        }

        boolean effective() {
            return "ACTIVE".equals(status) && "ACTIVE".equals(accountStatus);
        }
    }

    record WorkspaceUser(String id, String loginName, String displayName) {
    }

    record WorkspaceMember(String id, WorkspaceUser user, String role, String status, int version) {
    }

    record ProjectMemberRow(long id, long workspaceMemberId, long userId, String displayName, String role,
            String status, boolean effective, int version) {
        ProjectMember view() {
            return new ProjectMember(Long.toString(id), Long.toString(workspaceMemberId),
                    new ProjectUser(Long.toString(userId), displayName), role, status, effective, version);
        }
    }

    record ProjectUser(String id, String displayName) {
    }

    record ProjectMember(String id, String workspaceMemberId, ProjectUser user, String role, String status,
            boolean effective, int version) {
    }

}
