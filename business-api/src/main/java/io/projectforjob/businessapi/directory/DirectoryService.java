package io.projectforjob.businessapi.directory;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import io.projectforjob.businessapi.api.ApiFailure;
import io.projectforjob.businessapi.directory.DirectoryCommands.Reply;
import io.projectforjob.businessapi.directory.DirectoryCommands.Saved;
import io.projectforjob.businessapi.api.InsertId;
import io.projectforjob.businessapi.api.Paging.Page;
import io.projectforjob.businessapi.api.Paging;
import io.projectforjob.businessapi.directory.DirectoryData.Project;
import io.projectforjob.businessapi.directory.DirectoryData.ProjectMember;
import io.projectforjob.businessapi.directory.DirectoryData.ProjectMemberRow;
import io.projectforjob.businessapi.directory.DirectoryData.ProjectRow;
import io.projectforjob.businessapi.directory.DirectoryData.SpaceMemberRow;
import io.projectforjob.businessapi.directory.DirectoryData.Workspace;
import io.projectforjob.businessapi.directory.DirectoryData.WorkspaceMember;
import io.projectforjob.businessapi.directory.DirectoryData.WorkspaceRow;

@Service
public class DirectoryService {

    private final DirectoryMapper directory;
    private final DirectoryCommands commands;
    private final MembershipMapper memberships;
    private final ObjectMapper json;

    DirectoryService(DirectoryMapper directory, DirectoryCommands commands, MembershipMapper memberships, ObjectMapper json) {
        this.directory = directory;
        this.commands = commands;
        this.memberships = memberships;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public Page<Workspace> workspaces(String login, Paging paging) {
        var actor = user(login);
        return paging.result(directory.workspaces(actor, paging.pageSize(), paging.offset()).stream()
                .map(WorkspaceRow::view).toList(), directory.countWorkspaces(actor));
    }

    Reply createWorkspace(String login, String key, String name) {
        var actor = user(login);
        return commands.execute(actor, key, "POST /api/v1/workspaces", List.of(name), () -> user(login), current -> {
            var id = new InsertId();
            directory.createWorkspace(name, current, id);
            directory.addWorkspaceMember(id.getId(), current, "ADMIN");
            return new Saved(id.getId(), null, 201, workspace(current, id.getId()).view());
        });
    }

    @Transactional(readOnly = true)
    public Page<WorkspaceMember> workspaceMembers(String login, long workspaceId, Paging paging) {
        workspace(user(login), workspaceId);
        return paging.result(directory.workspaceMembers(workspaceId, paging.pageSize(), paging.offset()).stream()
                .map(SpaceMemberRow::view).toList(), directory.countWorkspaceMembers(workspaceId));
    }

    Reply addWorkspaceMember(String login, long workspaceId, String key, String targetLogin, String role) {
        var actor = user(login);
        var path = "POST /api/v1/workspaces/" + workspaceId + "/members";
        return commands.execute(actor, key, path, List.of(targetLogin, role),
                () -> lockWorkspace(actor, workspaceId), scope -> {
                    requireRole(scope.currentRole(), "ADMIN");
                    var target = directory.activeUserId(targetLogin);
                    if (target == null) {
                        throw ApiFailure.notFound();
                    }
                    var existing = directory.workspaceMemberByUser(workspaceId, target);
                    if (existing == null) {
                        directory.addWorkspaceMember(workspaceId, target, role);
                    } else if ("INACTIVE".equals(existing.status())) {
                        directory.restoreWorkspaceMember(existing.id(), role);
                    } else {
                        requireSameRole(existing.role(), role);
                    }
                    return new Saved(workspaceId, null, existing == null ? 201 : 200,
                            directory.workspaceMemberByUser(workspaceId, target).view());
                });
    }

    @Transactional(readOnly = true)
    public Page<Project> projects(String login, long workspaceId, Paging paging) {
        var actor = user(login);
        workspace(actor, workspaceId);
        return paging.result(directory.projects(actor, workspaceId, paging.pageSize(), paging.offset()).stream()
                .map(ProjectRow::view).toList(), directory.countProjects(actor, workspaceId));
    }

    Reply createProject(String login, long workspaceId, String key, String name, String goal) {
        var actor = user(login);
        var path = "POST /api/v1/workspaces/" + workspaceId + "/projects";
        return commands.execute(actor, key, path, List.of(name, goal),
                () -> lockWorkspace(actor, workspaceId), scope -> {
                    var member = directory.workspaceMemberByUser(scope.id(), actor);
                    var id = new InsertId();
                    directory.createProject(scope.id(), name, goal, actor, id);
                    directory.addProjectMember(scope.id(), id.getId(), member.id(), "OWNER");
                    return new Saved(scope.id(), id.getId(), 201, project(actor, id.getId()).view());
                });
    }

    @Transactional(readOnly = true)
    public Project project(String login, long projectId) {
        return project(user(login), projectId).view();
    }

    @Transactional(readOnly = true)
    public Page<ProjectMember> projectMembers(String login, long projectId, Paging paging) {
        project(user(login), projectId);
        return paging.result(directory.projectMembers(projectId, paging.pageSize(), paging.offset()).stream()
                .map(ProjectMemberRow::view).toList(), directory.countProjectMembers(projectId));
    }

    Reply addProjectMember(String login, long projectId, String key, long workspaceMemberId, String role) {
        var actor = user(login);
        var path = "POST /api/v1/projects/" + projectId + "/members";
        return commands.execute(actor, key, path, List.of(Long.toString(workspaceMemberId), role),
                () -> lockProject(actor, projectId), scope -> {
                    requireRole(scope.currentRole(), "OWNER");
                    if (!"ACTIVE".equals(scope.status())) {
                        throw ApiFailure.conflict("项目已归档");
                    }
                    var target = directory.workspaceMember(scope.workspaceId(), workspaceMemberId);
                    if (target == null || !target.effective()) {
                        throw ApiFailure.notFound();
                    }
                    var existing = directory.projectMemberByWorkspaceMember(projectId, workspaceMemberId);
                    if (existing == null) {
                        directory.addProjectMember(scope.workspaceId(), projectId, workspaceMemberId, role);
                    } else if ("INACTIVE".equals(existing.status())) {
                        directory.restoreProjectMember(existing.id(), role);
                    } else {
                        requireSameRole(existing.role(), role);
                    }
                    var added = directory.projectMemberByWorkspaceMember(projectId, workspaceMemberId);
                    if (existing != null && "ACTIVE".equals(existing.status())) {
                        return new Saved(scope.workspaceId(), projectId, 200, added.view());
                    }
                    var type = existing == null ? "PROJECT_MEMBER_ADDED" : "PROJECT_MEMBER_REJOINED";
                    var payload = json.writeValueAsString(Map.of("role", role, "version", added.version()));
                    return new Saved(scope.workspaceId(), projectId, existing == null ? 201 : 200, added.view(),
                            receiptId -> memberships.recordChange(projectId, actor, scope.memberId(), type, added.id(),
                                    receiptId, payload));
                });
    }

    @Transactional(readOnly = true)
    public DirectoryCommands.ReceiptView receipt(String login, String key) {
        return commands.receipt(user(login), key);
    }

    public long user(String login) {
        var userId = directory.activeUserId(login);
        if (userId == null) {
            throw ApiFailure.unauthenticated();
        }
        return userId;
    }

    public ProjectAccess projectAccess(long actor, long projectId) {
        return access(project(actor, projectId));
    }

    public ProjectAccess lockProjectAccess(long actor, long projectId) {
        return access(lockProject(actor, projectId));
    }

    public boolean canTakeTaskRole(long projectId, long memberId) {
        var member = directory.projectMember(projectId, memberId);
        return member != null && member.effective() && !"OBSERVER".equals(member.role());
    }

    private ProjectAccess access(ProjectRow row) {
        return new ProjectAccess(row.workspaceId(), row.id(), row.memberId(), row.currentRole(),
                "ACTIVE".equals(row.status()));
    }

    public record ProjectAccess(long workspaceId, long projectId, long memberId, String role, boolean active) {
        public boolean owner() {
            return "OWNER".equals(role);
        }

        public boolean canWork() {
            return owner() || "CONTRIBUTOR".equals(role);
        }
    }

    WorkspaceRow workspace(long actor, long workspaceId) {
        var scope = directory.workspace(actor, workspaceId);
        if (scope == null) {
            throw ApiFailure.notFound();
        }
        return scope;
    }

    private ProjectRow project(long actor, long projectId) {
        var scope = directory.project(actor, projectId);
        if (scope == null) {
            throw ApiFailure.notFound();
        }
        return scope;
    }

    WorkspaceRow lockWorkspace(long actor, long workspaceId) {
        workspace(actor, workspaceId);
        directory.lockWorkspace(workspaceId);
        return workspace(actor, workspaceId);
    }

    private ProjectRow lockProject(long actor, long projectId) {
        var scope = project(actor, projectId);
        directory.shareWorkspace(scope.workspaceId());
        directory.lockProject(projectId);
        return project(actor, projectId);
    }

    private void requireRole(String actual, String required) {
        if (!required.equals(actual)) {
            throw ApiFailure.forbidden();
        }
    }

    private void requireSameRole(String actual, String requested) {
        if (!actual.equals(requested)) {
            throw ApiFailure.conflict("成员已存在，请使用角色变更操作");
        }
    }
}
