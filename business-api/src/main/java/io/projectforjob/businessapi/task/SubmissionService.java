package io.projectforjob.businessapi.task;

import static java.time.ZoneOffset.UTC;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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
import io.projectforjob.businessapi.document.DocumentService;
import io.projectforjob.businessapi.task.SubmissionData.Action;
import io.projectforjob.businessapi.task.SubmissionData.Detail;
import io.projectforjob.businessapi.task.SubmissionData.Input;
import io.projectforjob.businessapi.task.SubmissionData.Result;
import io.projectforjob.businessapi.task.SubmissionData.Row;
import io.projectforjob.businessapi.task.SubmissionData.Summary;

@Service
class SubmissionService {

    private final SubmissionMapper submissions;
    private final TaskService tasks;
    private final DocumentService documents;
    private final DirectoryService directory;
    private final DirectoryCommands commands;
    private final ObjectMapper json;

    SubmissionService(SubmissionMapper submissions, TaskService tasks, DocumentService documents, DirectoryService directory,
            DirectoryCommands commands, ObjectMapper json) {
        this.submissions = submissions;
        this.tasks = tasks;
        this.documents = documents;
        this.directory = directory;
        this.commands = commands;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public Page<Summary> list(String login, long projectId, long taskId, Paging paging) {
        directory.projectAccess(directory.user(login), projectId);
        tasks.find(projectId, taskId);
        var items = submissions.list(projectId, taskId, paging.pageSize(), paging.offset()).stream()
                .map(SubmissionData.SummaryRow::view).toList();
        return paging.result(items, submissions.count(projectId, taskId));
    }

    @Transactional(readOnly = true)
    public Detail get(String login, long projectId, long taskId, long submissionId) {
        directory.projectAccess(directory.user(login), projectId);
        return detail(projectId, find(projectId, taskId, submissionId));
    }

    Reply submit(String login, long projectId, long taskId, String key, int version, Input input) {
        var actor = directory.user(login);
        return commands.execute(actor, key, path(projectId, taskId), List.of(version, input),
                () -> {
                    var access = directory.lockProjectAccess(actor, projectId);
                    return new ReviewContext(access, tasks.find(projectId, taskId), null);
                }, context -> {
                    var task = context.task();
                    var access = context.access();
                    tasks.requireActive(access);
                    if (!tasks.isCurrentExecutor(task, access)) {
                        throw ApiFailure.forbidden();
                    }
                    tasks.requireVersion(task, version);
                    if (!"IN_PROGRESS".equals(task.status())) {
                        throw ApiFailure.conflict("只有进行中的任务可以提交成果");
                    }

                    var reviewer = task.reviewerMemberId();
                    if (reviewer == null || reviewer.equals(access.memberId())
                            || !directory.canTakeTaskRole(projectId, reviewer)) {
                        throw violation("请先分派有效的成果确认人", "REVIEWER_REQUIRED");
                    }
                    for (var material : input.materials()) {
                        var revisionId = material.documentRevisionId();
                        if (revisionId != null && !documents.hasRevision(projectId, revisionId)) {
                            throw violation("请选择本项目中可用的文档版本", "MATERIAL_INVALID");
                        }
                    }
                    var id = new InsertId();
                    var sequence = task.submissionSeq() + 1;
                    submissions.create(projectId, taskId, sequence, access.memberId(), reviewer, version,
                            json.writeValueAsString(tasks.scope(task)), input.summary(), id);
                    int position = 0;
                    for (var material : input.materials()) {
                        submissions.addMaterial(projectId, id.getId(), material, position++);
                    }
                    tasks.requireUpdated(submissions.enterReview(projectId, taskId, id.getId(), sequence,
                            access.memberId(), version), projectId, taskId);
                    return recorded(actor, access, tasks.find(projectId, taskId), find(projectId, taskId, id.getId()),
                            201, "SUBMISSION_CREATED", recipients(access, reviewer));
                });
    }

    Reply finish(String login, long projectId, long taskId, long submissionId, String key, int version,
            Action action, String note) {
        var actor = directory.user(login);
        var actionPath = path(projectId, taskId) + "/" + submissionId + "/" + action.path();
        return commands.execute(actor, key, actionPath, List.of(version, note),
                () -> {
                    var access = directory.lockProjectAccess(actor, projectId);
                    return new ReviewContext(access, tasks.find(projectId, taskId), find(projectId, taskId, submissionId));
                }, context -> complete(actor, context, version, action, note));
    }

    private Saved complete(long actor, ReviewContext context, int version, Action action, String note) {
        var task = context.task();
        var access = context.access();
        var projectId = access.projectId();
        var submission = context.submission();
        var withdrawal = action == Action.WITHDRAW;
        tasks.requireActive(access);
        requireFinishRole(task, access, withdrawal);

        if (!Objects.equals(task.currentSubmissionId(), submission.id())) {
            throw ApiFailure.submissionStale(task.id(), task.version(), task.currentSubmissionId());
        }
        tasks.requireVersion(task, version);
        if (!"IN_REVIEW".equals(task.status()) || !"PENDING".equals(submission.status())) {
            throw new IllegalStateException("Current task submission is not awaiting review");
        }

        if (!withdrawal) {
            requireEffectiveExecutor(task);
            if (action == Action.APPROVE && (submission.summary().isBlank()
                    || submissions.materials(projectId, submission.id()).isEmpty())) {
                throw violation("成果材料不完整", "MATERIAL_INVALID");
            }
        }

        var now = LocalDateTime.now(UTC).truncatedTo(ChronoUnit.MILLIS);
        if (!withdrawal) {
            submissions.recordDecision(projectId, submission.id(), access.memberId(), action.name(), note, now);
        }
        if (submissions.finishSubmission(projectId, task.id(), submission.id(), action.submissionStatus, now,
                access.memberId(), withdrawal ? note : null) != 1) {
            throw new IllegalStateException("Could not finish the current submission");
        }
        tasks.requireUpdated(submissions.finishReview(projectId, task.id(), submission.id(), action.taskStatus,
                now, version, withdrawal ? null : access.memberId()), projectId, task.id());
        var recipients = withdrawal
                ? recipients(access, task.reviewerMemberId(), task.assigneeMemberId())
                : recipients(access, task.assigneeMemberId());
        return recorded(actor, access, tasks.find(projectId, task.id()), find(projectId, task.id(), submission.id()),
                200, action.event, recipients);
    }

    private void requireFinishRole(TaskData.Row task, ProjectAccess access, boolean withdrawal) {
        if (withdrawal) {
            if (!access.owner() && !tasks.isCurrentExecutor(task, access)) {
                throw ApiFailure.forbidden();
            }
        } else if (!access.canWork() || !Objects.equals(task.reviewerMemberId(), access.memberId())) {
            throw ApiFailure.forbidden();
        }
    }

    private void requireEffectiveExecutor(TaskData.Row task) {
        var assignee = task.assigneeMemberId();
        if (assignee == null || assignee.equals(task.reviewerMemberId())
                || !directory.canTakeTaskRole(task.projectId(), assignee)) {
            throw violation("执行者已失效，请由负责人撤回后重新分派", "ASSIGNEE_INACTIVE");
        }
    }

    private List<String> recipients(ProjectAccess access, Long... candidates) {
        return Arrays.stream(candidates).filter(Objects::nonNull).distinct()
                .filter(memberId -> memberId != access.memberId() && directory.canTakeTaskRole(access.projectId(), memberId))
                .map(String::valueOf).toList();
    }

    private Saved recorded(long actor, ProjectAccess access, TaskData.Row task, Row submission, int status,
            String event, List<String> recipients) {
        var detail = detail(access.projectId(), submission);
        var payload = json.createObjectNode().put("submissionId", detail.id())
                .put("submissionStatus", detail.status()).put("taskStatus", task.status());
        return tasks.recorded(actor, access, task, status, event, payload,
                new Result(tasks.detail(task, access), detail, detail.decision()), eventId -> {
                    if (!recipients.isEmpty()) {
                        submissions.enqueue(access.projectId(), eventId, json.writeValueAsString(recipients));
                    }
                });
    }

    private Detail detail(long projectId, Row row) {
        var materials = submissions.materials(projectId, row.id()).stream()
                .map(SubmissionData.MaterialRow::view).toList();
        var decision = submissions.decision(projectId, row.id());
        return row.detail(json.readTree(row.scopeSnapshot()), materials, decision == null ? null : decision.view());
    }

    private Row find(long projectId, long taskId, long submissionId) {
        var row = submissions.find(projectId, taskId, submissionId);
        if (row == null) {
            throw ApiFailure.notFound();
        }
        return row;
    }

    private String path(long projectId, long taskId) {
        return "POST /api/v1/projects/" + projectId + "/tasks/" + taskId + "/submissions";
    }

    static ApiFailure violation(String message, String reason) {
        return ApiFailure.precondition(message, Map.of("violations", List.of(Map.of("reason", reason))));
    }

    private record ReviewContext(ProjectAccess access, TaskData.Row task, Row submission) {
    }
}
