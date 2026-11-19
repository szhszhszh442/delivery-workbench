package io.projectforjob.businessapi.task;

import static io.projectforjob.businessapi.task.TaskData.stringId;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.LongConsumer;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import io.projectforjob.businessapi.api.ApiFailure;
import io.projectforjob.businessapi.api.InsertId;
import io.projectforjob.businessapi.api.Paging;
import io.projectforjob.businessapi.api.Paging.Page;
import io.projectforjob.businessapi.directory.DirectoryCommands;
import io.projectforjob.businessapi.directory.DirectoryCommands.Reply;
import io.projectforjob.businessapi.directory.DirectoryCommands.Saved;
import io.projectforjob.businessapi.directory.DirectoryService;
import io.projectforjob.businessapi.directory.DirectoryService.ProjectAccess;
import io.projectforjob.businessapi.task.TaskData.Assignment;
import io.projectforjob.businessapi.task.TaskData.Detail;
import io.projectforjob.businessapi.task.TaskData.Entry;
import io.projectforjob.businessapi.task.TaskData.Filters;
import io.projectforjob.businessapi.task.TaskData.Row;
import io.projectforjob.businessapi.task.TaskData.Scope;
import io.projectforjob.businessapi.task.TaskData.Summary;

@Service
class TaskService {

    private static final TypeReference<List<Entry>> ENTRIES = new TypeReference<>() {};
    private final TaskMapper tasks;
    private final DirectoryService directory;
    private final DirectoryCommands commands;
    private final ObjectMapper json;

    TaskService(TaskMapper tasks, DirectoryService directory, DirectoryCommands commands, ObjectMapper json) {
        this.tasks = tasks;
        this.directory = directory;
        this.commands = commands;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public Page<Summary> list(String login, long projectId, Paging paging, Filters filters) {
        directory.projectAccess(directory.user(login), projectId);
        var items = tasks.list(projectId, filters, paging.pageSize(), paging.offset()).stream()
                .map(TaskData.SummaryRow::view).toList();
        return paging.result(items, tasks.count(projectId, filters));
    }

    @Transactional(readOnly = true)
    public Detail get(String login, long projectId, long taskId) {
        var access = directory.projectAccess(directory.user(login), projectId);
        return detail(find(projectId, taskId), access);
    }

    Reply create(String login, long projectId, String key, Scope scope) {
        var actor = directory.user(login);
        return commands.execute(actor, key, "POST /api/v1/projects/" + projectId + "/tasks", List.of(scope),
                () -> directory.lockProjectAccess(actor, projectId), access -> {
                    if (!access.canWork()) {
                        throw ApiFailure.forbidden();
                    }
                    requireActive(access);
                    var id = new InsertId();
                    tasks.create(projectId, access.memberId(), scope, json.writeValueAsString(scope.acceptanceCriteria()),
                            json.writeValueAsString(scope.openQuestions()), id);
                    var created = find(projectId, id.getId());
                    return saved(actor, access, created, 201, "TASK_CREATED",
                            json.createObjectNode().put("status", created.status()));
                });
    }

    Reply updateScope(String login, long projectId, long taskId, String key, int version, Scope changes, String reason) {
        return update(login, projectId, taskId, key, "PATCH", "scope", List.of(version, changes, reason),
                (actor, context) -> {
                    var task = context.task();
                    var access = context.access();
                    requireScopeRole(task, access);
                    requireVersion(task, version);
                    requireDraftOrReady(task);
                    var merged = scope(task).apply(changes);
                    if ("READY".equals(task.status())) {
                        if (reason.isEmpty()) {
                            throw ApiFailure.precondition("修改待开始任务的范围需要填写原因", Map.of("field", "reason"));
                        }
                        requireReadyScope(merged);
                    }
                    requireUpdated(tasks.updateScope(projectId, taskId, task.status(), version, merged,
                            json.writeValueAsString(merged.acceptanceCriteria()), json.writeValueAsString(merged.openQuestions())),
                            projectId, taskId);
                    return saved(actor, access, find(projectId, taskId), 200, "TASK_SCOPE_UPDATED",
                            json.createObjectNode().put("previousVersion", version).put("reason", reason));
                });
    }

    Reply ready(String login, long projectId, long taskId, String key, int version) {
        return update(login, projectId, taskId, key, "POST", "ready", List.of(version), (actor, context) -> {
            var task = context.task();
            var access = context.access();
            requireScopeRole(task, access);
            requireVersion(task, version);
            if (!"DRAFT".equals(task.status())) {
                throw ApiFailure.conflict("只有待梳理任务可以整理就绪");
            }
            requireReadyScope(scope(task));
            requireUpdated(tasks.markReady(projectId, taskId, version), projectId, taskId);
            return saved(actor, access, find(projectId, taskId), 200, "TASK_READY",
                    json.createObjectNode().put("fromStatus", "DRAFT").put("toStatus", "READY"));
        });
    }

    Reply claim(String login, long projectId, long taskId, String key, int version) {
        return update(login, projectId, taskId, key, "POST", "claim", List.of(version), (actor, context) -> {
            var task = context.task();
            var access = context.access();
            if (!access.canWork()) {
                throw ApiFailure.forbidden();
            }
            requireReady(task);
            if (task.assigneeMemberId() != null) {
                throw ApiFailure.alreadyAssigned(task.id(), task.assigneeMemberId(), task.version());
            }
            requireVersion(task, version);
            if (Objects.equals(task.reviewerMemberId(), access.memberId())) {
                throw ApiFailure.precondition("成果确认人不能领取同一任务", Map.of("field", "reviewerMemberId"));
            }
            requireUpdated(tasks.claim(projectId, taskId, version, access.memberId()), projectId, taskId);
            return saved(actor, access, find(projectId, taskId), 200, "TASK_CLAIMED",
                    json.createObjectNode().put("assigneeMemberId", Long.toString(access.memberId())));
        });
    }

    Reply start(String login, long projectId, long taskId, String key, int version) {
        return update(login, projectId, taskId, key, "POST", "start", List.of(version), (actor, context) -> {
            var task = context.task();
            var access = context.access();
            if (!isCurrentExecutor(task, access)) {
                throw ApiFailure.forbidden();
            }
            requireVersion(task, version);
            requireReady(task);
            requireUpdated(tasks.start(projectId, taskId, version, access.memberId()), projectId, taskId);
            return saved(actor, access, find(projectId, taskId), 200, "TASK_STARTED",
                    json.createObjectNode().put("fromStatus", "READY").put("toStatus", "IN_PROGRESS"));
        });
    }

    Reply assign(String login, long projectId, long taskId, String key, int version, Assignment assignment) {
        return update(login, projectId, taskId, key, "PUT", "assignment", List.of(version, assignment), (actor, context) -> {
            var task = context.task();
            var access = context.access();
            if (!access.owner()) {
                throw ApiFailure.forbidden();
            }
            requireVersion(task, version);
            var assignee = assignment.assigneeMemberId();
            var reviewer = assignment.reviewerMemberId();
            requireAssignmentState(task, assignment);
            if (assignee != null && assignee.equals(reviewer)) {
                throw ApiFailure.precondition("执行者与确认人必须是不同的成员", Map.of("field", "reviewerMemberId"));
            }
            requireEligible(projectId, assignee, "assigneeMemberId");
            requireEligible(projectId, reviewer, "reviewerMemberId");
            if (Objects.equals(assignee, task.assigneeMemberId()) && Objects.equals(reviewer, task.reviewerMemberId())) {
                return new Saved(access.workspaceId(), projectId, 200, detail(task, access), null);
            }
            requireUpdated(tasks.assign(projectId, taskId, version, assignee, reviewer), projectId, taskId);
            return saved(actor, access, find(projectId, taskId), 200, "TASK_ASSIGNMENT_CHANGED",
                    json.createObjectNode()
                            .put("previousAssigneeMemberId", stringId(task.assigneeMemberId()))
                            .put("previousReviewerMemberId", stringId(task.reviewerMemberId()))
                            .put("assigneeMemberId", stringId(assignee))
                            .put("reviewerMemberId", stringId(reviewer))
                            .put("reason", assignment.reason()));
        });
    }

    private Reply update(String login, long projectId, long taskId, String key, String method, String action,
            List<?> fields, BiFunction<Long, TaskAccess, Saved> command) {
        var actor = directory.user(login);
        var path = method + " /api/v1/projects/" + projectId + "/tasks/" + taskId + "/" + action;
        return commands.execute(actor, key, path, fields,
                () -> new TaskAccess(directory.lockProjectAccess(actor, projectId), find(projectId, taskId)),
                context -> {
                    requireActive(context.access());
                    return command.apply(actor, context);
                });
    }

    private Saved saved(long actor, ProjectAccess access, Row row, int status, String event, ObjectNode payload) {
        return recorded(actor, access, row, status, event, payload, detail(row, access), eventId -> {});
    }

    Saved recorded(long actor, ProjectAccess access, Row row, int status, String event, ObjectNode payload,
            Object result, LongConsumer afterActivity) {
        payload.put("version", row.version());
        return new Saved(access.workspaceId(), access.projectId(), status, result, commandId -> {
            var eventId = new InsertId();
            tasks.recordActivity(access.projectId(), actor, access.memberId(), event, row.id(), commandId,
                    json.writeValueAsString(payload), eventId);
            afterActivity.accept(eventId.getId());
        });
    }

    Row find(long projectId, long taskId) {
        var row = tasks.find(projectId, taskId);
        if (row == null) {
            throw ApiFailure.notFound();
        }
        return row;
    }

    Scope scope(Row row) {
        return new Scope(row.title(), row.goal(), row.expectedOutcome(),
                json.readValue(row.acceptanceCriteria(), ENTRIES), json.readValue(row.openQuestions(), ENTRIES));
    }

    Detail detail(Row row, ProjectAccess access) {
        var actions = new ArrayList<String>();
        if (access.active()) {
            if ("DRAFT".equals(row.status()) && canEditDraft(row, access)) {
                actions.add("EDIT_SCOPE");
                actions.add("READY");
            } else if ("READY".equals(row.status()) && access.owner()) {
                actions.add("EDIT_SCOPE");
            }
            if (access.owner() && allowsAssignment(row.status())) {
                actions.add("ASSIGN");
            }
            if ("READY".equals(row.status()) && access.canWork()) {
                if (row.assigneeMemberId() == null && !Objects.equals(row.reviewerMemberId(), access.memberId())) {
                    actions.add("CLAIM");
                } else if (isCurrentExecutor(row, access)) {
                    actions.add("START");
                }
            }
            if ("IN_PROGRESS".equals(row.status()) && isCurrentExecutor(row, access)) {
                actions.add("SUBMIT");
            }
            if ("IN_REVIEW".equals(row.status())) {
                if (access.canWork() && Objects.equals(row.reviewerMemberId(), access.memberId())) {
                    actions.add("APPROVE");
                    actions.add("RETURN");
                }
                if (access.owner() || isCurrentExecutor(row, access)) {
                    actions.add("WITHDRAW");
                }
            }
        }
        return row.detail(scope(row), actions);
    }

    private boolean canEditDraft(Row task, ProjectAccess access) {
        return access.owner() || (access.canWork() && task.creatorMemberId() == access.memberId());
    }

    boolean isCurrentExecutor(Row task, ProjectAccess access) {
        return access.canWork() && Objects.equals(task.assigneeMemberId(), access.memberId());
    }

    private void requireScopeRole(Row task, ProjectAccess access) {
        if (!access.owner() && !("DRAFT".equals(task.status()) && canEditDraft(task, access))) {
            throw ApiFailure.forbidden();
        }
    }

    void requireActive(ProjectAccess access) {
        if (!access.active()) {
            throw ApiFailure.conflict("项目已归档");
        }
    }

    private void requireDraftOrReady(Row row) {
        if (!"DRAFT".equals(row.status()) && !"READY".equals(row.status())) {
            throw ApiFailure.conflict("当前任务状态不允许此操作");
        }
    }

    private void requireReady(Row row) {
        if (!"READY".equals(row.status())) {
            throw ApiFailure.conflict("只有待开始任务可以执行此操作");
        }
    }

    private boolean allowsAssignment(String status) {
        return switch (status) {
            case "DRAFT", "READY", "IN_PROGRESS", "IN_REVIEW" -> true;
            default -> false;
        };
    }

    private void requireAssignmentState(Row task, Assignment assignment) {
        if (!allowsAssignment(task.status())) {
            throw ApiFailure.conflict("当前任务状态不允许修改分派");
        }
        boolean changingExecutor = !Objects.equals(task.assigneeMemberId(), assignment.assigneeMemberId());
        if ("IN_REVIEW".equals(task.status()) && (changingExecutor || assignment.reviewerMemberId() == null)) {
            throw ApiFailure.conflict("待确认时只能更换确认人，变更执行者请先撤回");
        }
        if ("IN_PROGRESS".equals(task.status()) && changingExecutor
                && (assignment.assigneeMemberId() == null || assignment.reason().isBlank())) {
            throw ApiFailure.precondition("进行中移交任务需要接任人和原因", Map.of("field", "reason"));
        }
    }

    void requireVersion(Row row, int version) {
        if (row.version() != version) {
            throw ApiFailure.versionConflict(row.id(), row.version());
        }
    }

    void requireUpdated(int changed, long projectId, long taskId) {
        if (changed != 1) {
            throw ApiFailure.versionConflict(taskId, find(projectId, taskId).version());
        }
    }

    private void requireEligible(long projectId, Long member, String field) {
        if (member != null && !directory.canTakeTaskRole(projectId, member)) {
            throw ApiFailure.precondition("请选择本项目中有效的负责人或执行成员", Map.of("field", field));
        }
    }

    private void requireReadyScope(Scope scope) {
        var missing = new ArrayList<String>();
        if (scope.goal().isBlank()) missing.add("goal");
        if (scope.expectedOutcome().isBlank()) missing.add("expectedOutcome");
        if (scope.acceptanceCriteria().isEmpty()) missing.add("acceptanceCriteria");
        if (!missing.isEmpty()) {
            throw ApiFailure.precondition("请补齐任务目标、预期成果和至少一项完成条件", Map.of("missing", missing));
        }
    }

    private record TaskAccess(ProjectAccess access, Row task) {
    }
}
