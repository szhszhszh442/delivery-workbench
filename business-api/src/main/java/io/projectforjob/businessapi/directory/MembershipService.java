package io.projectforjob.businessapi.directory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import io.projectforjob.businessapi.api.ApiFailure;
import io.projectforjob.businessapi.directory.DirectoryCommands.Reply;
import io.projectforjob.businessapi.directory.DirectoryCommands.Saved;
import io.projectforjob.businessapi.directory.DirectoryData.ProjectMemberRow;
import io.projectforjob.businessapi.directory.DirectoryData.SpaceMemberRow;
import io.projectforjob.businessapi.directory.DirectoryService.ProjectAccess;
import io.projectforjob.businessapi.directory.MembershipMapper.Responsibilities;

@Service
class MembershipService {

    enum RemovalMode {
        REQUIRE_CLEAR, REVOKE_NOW
    }

    private final MembershipMapper memberships;
    private final DirectoryMapper directory;
    private final DirectoryService access;
    private final DirectoryCommands commands;
    private final ObjectMapper json;

    MembershipService(MembershipMapper memberships, DirectoryMapper directory, DirectoryService access,
            DirectoryCommands commands, ObjectMapper json) {
        this.memberships = memberships;
        this.directory = directory;
        this.access = access;
        this.commands = commands;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public Responsibilities responsibilities(String login, long projectId, long memberId) {
        var actor = access.user(login);
        var project = access.projectAccess(actor, projectId);
        var target = projectMember(projectId, memberId);
        if (!project.owner() && target.userId() != actor) {
            throw ApiFailure.forbidden();
        }
        return new Responsibilities(Long.toString(memberId), target.role(), target.effective(),
                isLastOwner(projectId, target), memberships.openResponsibilities(projectId, memberId).stream()
                        .map(MembershipMapper.ResponsibilityRow::view).toList());
    }

    Reply changeProjectRole(String login, long projectId, long memberId, String key, int version,
            String role, String reason) {
        var actor = access.user(login);
        return commands.execute(actor, key, "PATCH /api/v1/projects/" + projectId + "/members/" + memberId,
                List.of(version, role, reason), () -> access.lockProjectAccess(actor, projectId), context -> {
                    if (!context.owner()) {
                        throw ApiFailure.forbidden();
                    }
                    var target = projectMember(projectId, memberId);
                    checkMember("PROJECT_MEMBER", target.id(), target.version(), version, target.status());
                    if (role.equals(target.role())) {
                        return new Saved(context.workspaceId(), projectId, 200, target.view());
                    }
                    if (isLastOwner(projectId, target)) {
                        throw ApiFailure.lastOwnerRequired(Map.of("projectId", Long.toString(projectId)));
                    }
                    checkUpdated(memberships.changeProjectRole(projectId, memberId, version, role));
                    var updated = projectMember(projectId, memberId);
                    return projectChange(context, actor, target, updated, "PROJECT_MEMBER_ROLE_CHANGED", reason);
                });
    }

    Reply removeProjectMember(String login, long projectId, long memberId, String key, int version,
            String reason, RemovalMode mode) {
        var actor = access.user(login);
        return commands.execute(actor, key, "POST /api/v1/projects/" + projectId + "/members/" + memberId + "/remove",
                List.of(version, reason, mode), () -> access.lockProjectAccess(actor, projectId), context -> {
                    var target = projectMember(projectId, memberId);
                    if (!context.owner() && target.userId() != actor) {
                        throw ApiFailure.forbidden();
                    }
                    checkMember("PROJECT_MEMBER", target.id(), target.version(), version, target.status());
                    if (isLastOwner(projectId, target)) {
                        throw ApiFailure.lastOwnerRequired(Map.of("projectId", Long.toString(projectId)));
                    }
                    if (mode == RemovalMode.REQUIRE_CLEAR && memberships.hasOpenResponsibilities(projectId, memberId)) {
                        throw ApiFailure.precondition("请先移交未完成的执行和确认职责",
                                Map.of("reason", "OPEN_RESPONSIBILITIES", "memberId", Long.toString(memberId)));
                    }
                    checkUpdated(memberships.removeProjectMember(projectId, memberId, version));
                    var updated = projectMember(projectId, memberId);
                    return projectChange(context, actor, target, updated, "PROJECT_MEMBER_REMOVED", reason);
                });
    }

    Reply changeWorkspaceRole(String login, long workspaceId, long memberId, String key, int version, String role) {
        var actor = access.user(login);
        return commands.execute(actor, key, "PATCH /api/v1/workspaces/" + workspaceId + "/members/" + memberId,
                List.of(version, role), () -> access.lockWorkspace(actor, workspaceId), context -> {
                    if (!"ADMIN".equals(context.currentRole())) {
                        throw ApiFailure.forbidden();
                    }
                    var target = workspaceMember(workspaceId, memberId);
                    checkMember("WORKSPACE_MEMBER", target.id(), target.version(), version, target.status());
                    if (role.equals(target.role())) {
                        return new Saved(workspaceId, null, 200, target.view());
                    }
                    requireAnotherAdmin(workspaceId, target);
                    checkUpdated(memberships.changeWorkspaceRole(workspaceId, memberId, version, role));
                    return new Saved(workspaceId, null, 200, workspaceMember(workspaceId, memberId).view());
                });
    }

    Reply removeWorkspaceMember(String login, long workspaceId, long memberId, String key, int version,
            String reason, RemovalMode mode) {
        var actor = access.user(login);
        var path = "POST /api/v1/workspaces/" + workspaceId + "/members/" + memberId + "/remove";
        return commands.execute(actor, key, path, List.of(version, reason, mode),
                () -> access.lockWorkspace(actor, workspaceId), context -> {
                    var target = workspaceMember(workspaceId, memberId);
                    if (!"ADMIN".equals(context.currentRole()) && target.userId() != actor) {
                        throw ApiFailure.forbidden();
                    }
                    checkMember("WORKSPACE_MEMBER", target.id(), target.version(), version, target.status());
                    requireAnotherAdmin(workspaceId, target);
                    var affected = memberships.activeProjects(workspaceId, memberId);
                    var ownerBlocks = new ArrayList<String>();
                    var dutyBlocks = new ArrayList<String>();
                    // The exclusive workspace lock stabilizes the project IDs while taking the project locks.
                    for (var member : affected) {
                        directory.lockProject(member.projectId());
                        if (target.effective() && "OWNER".equals(member.role())
                                && memberships.otherActiveOwners(member.projectId(), member.memberId()) == 0) {
                            ownerBlocks.add(Long.toString(member.projectId()));
                        }
                        if (mode == RemovalMode.REQUIRE_CLEAR
                                && memberships.hasOpenResponsibilities(member.projectId(), member.memberId())) {
                            dutyBlocks.add(Long.toString(member.projectId()));
                        }
                    }
                    if (!ownerBlocks.isEmpty()) {
                        throw ApiFailure.lastOwnerRequired(Map.of("projectIds", ownerBlocks));
                    }
                    if (!dutyBlocks.isEmpty()) {
                        throw ApiFailure.precondition("请由相关项目负责人先移交未完成职责",
                                Map.of("reason", "OPEN_RESPONSIBILITIES", "projectIds", dutyBlocks));
                    }

                    if (memberships.removeWorkspaceProjectMemberships(workspaceId, memberId) != affected.size()) {
                        throw ApiFailure.conflict("成员资格已变化，请刷新后再试");
                    }
                    checkUpdated(memberships.removeWorkspaceMember(workspaceId, memberId, version));
                    var payload = json.writeValueAsString(Map.of("reason", reason, "workspaceMemberId", Long.toString(memberId),
                            "previousStatus", "ACTIVE", "status", "INACTIVE"));
                    return new Saved(workspaceId, null, 200, workspaceMember(workspaceId, memberId).view(),
                            receiptId -> {
                                for (var member : affected) {
                                    memberships.recordChange(member.projectId(), actor, null, "WORKSPACE_MEMBER_REMOVED",
                                            member.memberId(), receiptId, payload);
                                }
                            });
                });
    }

    private ProjectMemberRow projectMember(long projectId, long memberId) {
        var member = directory.projectMember(projectId, memberId);
        if (member == null) {
            throw ApiFailure.notFound();
        }
        return member;
    }

    private SpaceMemberRow workspaceMember(long workspaceId, long memberId) {
        var member = directory.workspaceMember(workspaceId, memberId);
        if (member == null) {
            throw ApiFailure.notFound();
        }
        return member;
    }

    private boolean isLastOwner(long projectId, ProjectMemberRow target) {
        return target.effective() && "OWNER".equals(target.role())
                && memberships.otherActiveOwners(projectId, target.id()) == 0;
    }

    private void requireAnotherAdmin(long workspaceId, SpaceMemberRow target) {
        if (target.effective() && "ADMIN".equals(target.role())
                && memberships.otherActiveAdmins(workspaceId, target.id()) == 0) {
            throw ApiFailure.precondition("空间须保留至少一名有效管理员", Map.of("reason", "LAST_ADMIN_REQUIRED"));
        }
    }

    private Saved projectChange(ProjectAccess context, long actor, ProjectMemberRow previous,
            ProjectMemberRow updated, String type, String reason) {
        var payload = json.writeValueAsString(Map.of("previousRole", previous.role(), "role", updated.role(),
                "previousStatus", previous.status(), "status", updated.status(), "version", updated.version(), "reason", reason));
        return new Saved(context.workspaceId(), context.projectId(), 200, updated.view(),
                receiptId -> memberships.recordChange(context.projectId(), actor, context.memberId(), type,
                        updated.id(), receiptId, payload));
    }

    private void checkMember(String resource, long id, int current, int version, String status) {
        if (current != version) {
            throw ApiFailure.versionConflict(resource, id, current, "成员资格已变化，请刷新后再试");
        }
        if (!"ACTIVE".equals(status)) {
            throw ApiFailure.conflict("成员已失效");
        }
    }

    private void checkUpdated(int changed) {
        if (changed != 1) {
            throw ApiFailure.conflict("成员资格已变化，请刷新后再试");
        }
    }
}
