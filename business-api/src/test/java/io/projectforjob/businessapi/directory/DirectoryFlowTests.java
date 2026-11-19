package io.projectforjob.businessapi.directory;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(DirectoryFlowTests.SubmissionFailureConfiguration.class)
class DirectoryFlowTests {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwords;

    @Autowired
    ObjectMapper json;

    @Autowired
    FailDeliveryOnce deliveryFailure;

    private final List<String> testUsers = new ArrayList<>();

    @AfterEach
    void removeFixtures() {
        for (var user : testUsers) {
            var userId = jdbc.queryForObject("SELECT id FROM app_user WHERE login_name = ?", Long.class, user);
            jdbc.update("""
                    DELETE d FROM notification_delivery d JOIN project p ON p.id = d.project_id
                    JOIN workspace w ON w.id = p.workspace_id WHERE w.created_by_user_id = ?
                    """, userId);
            jdbc.update("""
                    UPDATE task t JOIN project p ON p.id = t.project_id JOIN workspace w ON w.id = p.workspace_id
                    SET t.status = 'IN_PROGRESS', t.current_submission_id = NULL
                    WHERE w.created_by_user_id = ? AND t.current_submission_id IS NOT NULL
                    """, userId);
            jdbc.update("""
                    DELETE d FROM submission_decision d JOIN project p ON p.id = d.project_id
                    JOIN workspace w ON w.id = p.workspace_id WHERE w.created_by_user_id = ?
                    """, userId);
            jdbc.update("""
                    DELETE m FROM submission_material m JOIN project p ON p.id = m.project_id
                    JOIN workspace w ON w.id = p.workspace_id WHERE w.created_by_user_id = ?
                    """, userId);
            jdbc.update("""
                    DELETE s FROM task_submission s JOIN project p ON p.id = s.project_id
                    JOIN workspace w ON w.id = p.workspace_id WHERE w.created_by_user_id = ?
                    """, userId);
            jdbc.update("""
                    UPDATE document d JOIN project p ON p.id = d.project_id JOIN workspace w ON w.id = p.workspace_id
                    SET d.current_revision_id = NULL WHERE w.created_by_user_id = ?
                    """, userId);
            jdbc.update("""
                    DELETE r FROM document_revision r JOIN project p ON p.id = r.project_id
                    JOIN workspace w ON w.id = p.workspace_id WHERE w.created_by_user_id = ?
                    """, userId);
            jdbc.update("""
                    DELETE d FROM document d JOIN project p ON p.id = d.project_id
                    JOIN workspace w ON w.id = p.workspace_id WHERE w.created_by_user_id = ?
                    """, userId);
            jdbc.update("""
                    DELETE a FROM activity_event a JOIN project p ON p.id = a.project_id
                    JOIN workspace w ON w.id = p.workspace_id WHERE w.created_by_user_id = ?
                    """, userId);
            jdbc.update("""
                    DELETE t FROM task t JOIN project p ON p.id = t.project_id
                    JOIN workspace w ON w.id = p.workspace_id WHERE w.created_by_user_id = ?
                    """, userId);
        }
        for (var user : testUsers) {
            var userId = jdbc.queryForObject("SELECT id FROM app_user WHERE login_name = ?", Long.class, user);
            jdbc.update("DELETE FROM command_receipt WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM SPRING_SESSION WHERE PRINCIPAL_NAME = ?", user);
        }
        for (var user : testUsers) {
            var userId = jdbc.queryForObject("SELECT id FROM app_user WHERE login_name = ?", Long.class, user);
            jdbc.update("""
                    DELETE pm FROM project_member pm JOIN workspace w ON w.id = pm.workspace_id
                    WHERE w.created_by_user_id = ?
                    """, userId);
            jdbc.update("""
                    DELETE p FROM project p JOIN workspace w ON w.id = p.workspace_id
                    WHERE w.created_by_user_id = ?
                    """, userId);
            jdbc.update("""
                    DELETE wm FROM workspace_member wm JOIN workspace w ON w.id = wm.workspace_id
                    WHERE w.created_by_user_id = ?
                    """, userId);
            jdbc.update("DELETE FROM workspace WHERE created_by_user_id = ?", userId);
        }
        for (var user : testUsers) {
            jdbc.update("DELETE FROM app_user WHERE login_name = ?", user);
        }
    }

    @Test
    void createsSpacesAndProjectsWithSeparateMembershipBoundaries() throws Exception {
        try (var owner = createAccount(); var observer = createAccount(); var outside = createAccount()) {
            var workspaceKey = key();
            var name = json.createObjectNode().put("name", "交付空间");
            assertError(owner.post("/workspaces", name, null), 400, "INVALID_REQUEST");
            var space = data(owner.post("/workspaces", name, workspaceKey), 201);
            var workspaceId = space.get("id").asString();
            assertThat(space.get("currentRole").asString()).isEqualTo("ADMIN");
            var workspacePath = "/workspaces/" + workspaceId;

            var replay = owner.post("/workspaces", name, workspaceKey);
            assertThat(data(replay, 201)).isEqualTo(space);
            assertThat(replay.body().get("replayed").booleanValue()).isTrue();
            assertError(owner.post("/workspaces", name.deepCopy().put("name", "修改名称"), workspaceKey),
                    409, "IDEMPOTENCY_KEY_REUSED");
            var receipt = data(owner.get("/commands/" + workspaceKey), 200);
            assertThat(receipt.get("action").asString()).isEqualTo("POST /api/v1/workspaces");
            assertThat(receipt.get("result")).isEqualTo(space);
            assertError(observer.get("/commands/" + workspaceKey), 404, "NOT_FOUND");
            assertError(observer.get(workspacePath + "/members"), 404, "NOT_FOUND");

            var emptyPage = data(owner.get("/workspaces?page=2&pageSize=1"), 200);
            assertThat(emptyPage.get("total").asInt()).isEqualTo(1);
            assertThat(emptyPage.get("items").size()).isZero();

            var foreign = data(outside.post("/workspaces", name, key()), 201);
            var foreignMembers = data(outside.get("/workspaces/" + foreign.get("id").asString() + "/members"), 200);
            var foreignMemberId = foreignMembers.get("items").get(0).get("id").asString();
            var observerMembership = json.createObjectNode().put("loginName", observer.login()).put("role", "MEMBER");
            var member = data(owner.post(workspacePath + "/members", observerMembership, key()), 201);
            var outsideMembership = json.createObjectNode().put("loginName", outside.login()).put("role", "ADMIN");
            assertError(observer.post(workspacePath + "/members", outsideMembership, key()), 403, "FORBIDDEN");
            data(owner.post(workspacePath + "/members", outsideMembership, key()), 201);

            var projectInput = json.createObjectNode().put("name", "团队邀请").put("goal", "交付邀请流程");
            assertError(owner.post(workspacePath + "/projects", projectInput, workspaceKey),
                    409, "IDEMPOTENCY_KEY_REUSED");
            var project = data(owner.post(workspacePath + "/projects", projectInput, key()), 201);
            assertThat(project.get("currentRole").asString()).isEqualTo("OWNER");
            assertThat(project.get("createdAt").asString()).endsWith("Z");
            var projectPath = "/projects/" + project.get("id").asString();
            assertThat(data(owner.get(workspacePath + "/projects"), 200).get("total").asInt()).isEqualTo(1);
            assertThat(data(outside.get(workspacePath + "/projects"), 200).get("total").asInt()).isZero();
            assertError(outside.get(projectPath), 404, "NOT_FOUND");
            assertError(observer.get(projectPath + "/members"), 404, "NOT_FOUND");

            var wrongSpace = json.createObjectNode().put("workspaceMemberId", foreignMemberId).put("role", "OBSERVER");
            assertError(owner.post(projectPath + "/members", wrongSpace, key()), 404, "NOT_FOUND");
            var observerRole = wrongSpace.deepCopy().put("workspaceMemberId", member.get("id").asString());
            var joined = data(owner.post(projectPath + "/members", observerRole, key()), 201);
            assertThat(joined.get("workspaceMemberId").asString()).isEqualTo(member.get("id").asString());
            assertThat(joined.get("user").get("id").asString())
                    .isEqualTo(data(observer.get("/me"), 200).get("id").asString());
            var repeated = data(owner.post(projectPath + "/members", observerRole, key()), 200);
            assertThat(repeated).isEqualTo(joined);
            assertError(owner.post(projectPath + "/members", observerRole.deepCopy().put("role", "OWNER"), key()),
                    409, "STATE_CONFLICT");
            assertThat(data(observer.get(projectPath), 200).get("currentRole").asString()).isEqualTo("OBSERVER");
            assertThat(data(observer.get(projectPath + "/members"), 200).get("total").asInt()).isEqualTo(2);
            assertError(observer.post(projectPath + "/members", observerRole, key()), 403, "FORBIDDEN");
        }
    }

    @Test
    void concurrentWorkspaceCreationWithTheSameKeyCommitsOnce() throws Exception {
        try (var first = createAccount(); var second = signIn(first.login(), first.password());
                var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var requestKey = key();
            var input = json.createObjectNode().put("name", "并发创建");
            var left = workers.submit(() -> first.post("/workspaces", input, requestKey));
            var right = workers.submit(() -> second.post("/workspaces", input, requestKey));
            var one = left.get();
            var two = right.get();
            assertThat(data(one, 201)).isEqualTo(data(two, 201));
            assertThat(one.body().path("replayed").asBoolean()).isNotEqualTo(two.body().path("replayed").asBoolean());
            assertThat(data(first.get("/workspaces"), 200).get("total").asInt()).isEqualTo(1);
        }
    }

    @Test
    void preparesAndAssignsTasksWithRoleChecksVersionsAndReceipts() throws Exception {
        try (var owner = createAccount(); var contributor = createAccount(); var observer = createAccount();
                var otherPage = signIn(owner.login(), owner.password())) {
            var space = data(owner.post("/workspaces", json.createObjectNode().put("name", "交付任务"), key()), 201);
            var workspacePath = "/workspaces/" + space.get("id").asString();
            var project = data(owner.post(workspacePath + "/projects",
                    json.createObjectNode().put("name", "邀请功能").put("goal", "本期交付"), key()), 201);
            var projectId = project.get("id").asString();
            var projectPath = "/projects/" + projectId;
            var taskPath = projectPath + "/tasks";
            var conflictingProject = data(owner.post(workspacePath + "/projects",
                    json.createObjectNode().put("name", "其他项目").put("goal", "独立范围"), key()), 201);
            var conflictingPath = "/projects/" + conflictingProject.get("id").asString();
            var foreignMemberId = data(owner.get(conflictingPath + "/members"), 200)
                    .get("items").get(0).get("id").asString();
            var contributorId = addProjectMember(owner, workspacePath, projectPath, contributor, "CONTRIBUTOR");
            var observerId = addProjectMember(owner, workspacePath, projectPath, observer, "OBSERVER");
            var ownerId = data(owner.get(projectPath + "/members"), 200).get("items").valueStream()
                    .filter(member -> "OWNER".equals(member.get("role").asString())).findFirst().orElseThrow()
                    .get("id").asString();

            var createKey = key();
            var titleOnly = json.createObjectNode().put("title", "邀请 % 验证");
            assertError(observer.post(taskPath, titleOnly, key()), 403, "FORBIDDEN");
            var created = data(contributor.post(taskPath, titleOnly, createKey), 201);
            assertThat(created.get("status").asString()).isEqualTo("DRAFT");
            assertThat(created.get("creatorMemberId").asString()).isEqualTo(contributorId);
            assertThat(created.get("assigneeMemberId").isNull()).isTrue();
            var replay = contributor.post(taskPath, titleOnly, createKey);
            assertThat(data(replay, 201)).isEqualTo(created);
            assertThat(replay.body().get("replayed").asBoolean()).isTrue();
            var taskId = created.get("id").asString();
            var detail = taskPath + "/" + taskId;
            assertError(owner.get(conflictingPath + "/tasks/" + taskId), 404, "NOT_FOUND");
            assertThat(data(observer.get(detail), 200).get("allowedActions").isEmpty()).isTrue();

            var move = json.createObjectNode().put("expectedVersion", 1);
            assertError(observer.post(detail + "/ready", move, key()), 403, "FORBIDDEN");
            var blocked = contributor.post(detail + "/ready", move, key());
            assertError(blocked, 422, "PRECONDITION_FAILED");
            assertThat(blocked.body().get("error").get("details").get("missing").size()).isEqualTo(3);
            var edit = json.createObjectNode().put("expectedVersion", 1)
                    .put("goal", "让用户邀请新成员").put("expectedOutcome", "提供可使用的邀请流程");
            edit.putArray("acceptanceCriteria").addObject().put("key", "ac1").put("text", "链接有效时可接受邀请");
            edit.putArray("openQuestions").addObject().put("key", "q1").put("text", "后续是否提供自定义文案");
            var updated = data(contributor.write("PATCH", detail + "/scope", edit, key()), 200);
            assertThat(updated.get("version").asInt()).isEqualTo(2);

            var ready = data(contributor.post(detail + "/ready", move.deepCopy().put("expectedVersion", 2), key()), 200);
            assertThat(ready.get("status").asString()).isEqualTo("READY");
            assertThat(ready.get("openQuestions").size()).isEqualTo(1);
            assertThat(ready.get("version").asInt()).isEqualTo(3);
            var ownerEdit = json.createObjectNode().put("expectedVersion", 3).put("goal", "补充邀请目标");
            assertError(owner.write("PATCH", detail + "/scope", ownerEdit, key()), 422, "PRECONDITION_FAILED");
            var assign = json.createObjectNode().put("expectedVersion", 3)
                    .put("assigneeMemberId", contributorId).put("reviewerMemberId", ownerId);
            assertError(contributor.write("PUT", detail + "/assignment", assign, key()), 403, "FORBIDDEN");
            assertError(owner.write("PUT", detail + "/assignment",
                    assign.deepCopy().put("assigneeMemberId", observerId), key()), 422, "PRECONDITION_FAILED");
            assertError(owner.write("PUT", detail + "/assignment",
                    assign.deepCopy().put("assigneeMemberId", foreignMemberId), key()), 422, "PRECONDITION_FAILED");
            assertError(owner.write("PUT", detail + "/assignment",
                    assign.deepCopy().put("reviewerMemberId", contributorId), key()), 422, "PRECONDITION_FAILED");
            var assignKey = key();
            var assigned = data(owner.write("PUT", detail + "/assignment", assign, assignKey), 200);
            assertThat(assigned.get("assigneeMemberId").asString()).isEqualTo(contributorId);
            assertThat(assigned.get("version").asInt()).isEqualTo(4);
            var obsolete = otherPage.write("PUT", detail + "/assignment",
                    assign.deepCopy().putNull("assigneeMemberId"), key());
            assertError(obsolete, 409, "VERSION_CONFLICT");
            assertThat(obsolete.body().get("error").get("details").get("currentVersion").asInt()).isEqualTo(4);
            var assignmentReplay = otherPage.write("PUT", detail + "/assignment", assign, assignKey);
            assertThat(data(assignmentReplay, 200)).isEqualTo(assigned);
            assertThat(assignmentReplay.body().get("replayed").asBoolean()).isTrue();

            var found = data(observer.get(taskPath + "?status=READY&reviewerMemberId=" + ownerId + "&q=%25"), 200);
            assertThat(found.get("total").asInt()).isEqualTo(1);
            assertThat(found.get("items").get(0).get("id").asString()).isEqualTo(taskId);
            assertThat(found.get("items").get(0).has("goal")).isFalse();
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM activity_event a
                    JOIN command_receipt r ON r.id = a.command_id AND r.project_id = a.project_id
                    WHERE a.project_id = ? AND a.entity_id = ? AND a.entity_type = 'TASK'
                    """, Integer.class, Long.parseLong(projectId), Long.parseLong(taskId))).isEqualTo(4);

            var clear = assign.deepCopy().put("expectedVersion", 4).putNull("assigneeMemberId");
            var cleared = data(owner.write("PUT", detail + "/assignment", clear, key()), 200);
            assertThat(cleared.get("assigneeMemberId").isNull()).isTrue();
            assertThat(cleared.get("version").asInt()).isEqualTo(5);
            var unchanged = data(owner.write("PUT", detail + "/assignment",
                    clear.deepCopy().put("expectedVersion", 5), key()), 200);
            assertThat(unchanged.get("version").asInt()).isEqualTo(5);
        }
    }

    @Test
    void competingClaimsHaveOneWinnerAndOnlyTheCurrentExecutorCanStart() throws Exception {
        try (var owner = createAccount(); var first = createAccount(); var second = createAccount();
                var observer = createAccount()) {
            var space = data(owner.post("/workspaces", json.createObjectNode().put("name", "领取验证"), key()), 201);
            var workspacePath = "/workspaces/" + space.get("id").asString();
            var project = data(owner.post(workspacePath + "/projects",
                    json.createObjectNode().put("name", "团队邀请").put("goal", "本期交付"), key()), 201);
            var projectPath = "/projects/" + project.get("id").asString();
            var firstId = addProjectMember(owner, workspacePath, projectPath, first, "CONTRIBUTOR");
            var secondId = addProjectMember(owner, workspacePath, projectPath, second, "CONTRIBUTOR");
            addProjectMember(owner, workspacePath, projectPath, observer, "OBSERVER");
            var ownerId = data(owner.get(projectPath + "/members"), 200).get("items").valueStream()
                    .filter(member -> "OWNER".equals(member.get("role").asString())).findFirst().orElseThrow()
                    .get("id").asString();
            var ready = newReadyTask(owner, projectPath);
            var taskId = ready.get("id").asString();
            var detail = projectPath + "/tasks/" + taskId;
            var assignedReviewer = data(owner.write("PUT", detail + "/assignment", json.createObjectNode()
                    .put("expectedVersion", ready.get("version").asInt())
                    .putNull("assigneeMemberId").put("reviewerMemberId", ownerId), key()), 200);
            var version = assignedReviewer.get("version").asInt();
            var body = json.createObjectNode().put("expectedVersion", version);

            assertThat(data(first.get(detail), 200).get("allowedActions").toString()).contains("CLAIM");
            assertThat(data(owner.get(detail), 200).get("allowedActions").toString()).doesNotContain("CLAIM", "START");
            assertError(owner.post(detail + "/claim", body, key()), 422, "PRECONDITION_FAILED");
            assertError(observer.post(detail + "/claim", body, key()), 403, "FORBIDDEN");
            assertError(first.post(detail + "/start", body, key()), 403, "FORBIDDEN");
            assertError(first.post(detail + "/claim", body.deepCopy().put("expectedVersion", version - 1), key()),
                    409, "VERSION_CONFLICT");

            var firstKey = key();
            var secondKey = key();
            var firstPost = first.commandRequest("POST", detail + "/claim", body, firstKey);
            var secondPost = second.commandRequest("POST", detail + "/claim", body, secondKey);
            var results = race(() -> first.send(firstPost), () -> second.send(secondPost));
            assertThat(results.stream().map(Response::status).toList()).containsExactlyInAnyOrder(200, 409);
            boolean firstWon = results.get(0).status() == 200;
            var winner = firstWon ? first : second;
            var loser = firstWon ? second : first;
            var winnerId = firstWon ? firstId : secondId;
            var winnerKey = firstWon ? firstKey : secondKey;
            var loserKey = firstWon ? secondKey : firstKey;
            var claimed = data(results.get(firstWon ? 0 : 1), 200);
            var rejected = results.get(firstWon ? 1 : 0);
            assertError(rejected, 409, "ALREADY_ASSIGNED");
            assertThat(rejected.body().get("error").get("details").get("assigneeMemberId").asString())
                    .isEqualTo(winnerId);
            assertError(loser.get("/commands/" + loserKey), 404, "NOT_FOUND");
            assertThat(claimed.get("assigneeMemberId").asString()).isEqualTo(winnerId);
            assertThat(claimed.get("reviewerMemberId").asString()).isEqualTo(ownerId);
            assertThat(claimed.get("status").asString()).isEqualTo("READY");
            assertThat(claimed.get("version").asInt()).isEqualTo(version + 1);
            assertThat(claimed.get("allowedActions").toString()).contains("START").doesNotContain("CLAIM");
            assertThat(data(loser.get(detail), 200).get("allowedActions").toString()).doesNotContain("CLAIM", "START");

            var start = json.createObjectNode().put("expectedVersion", version + 1);
            assertError(owner.post(detail + "/start", start, key()), 403, "FORBIDDEN");
            assertError(loser.post(detail + "/start", start, key()), 403, "FORBIDDEN");
            assertError(winner.post(detail + "/start", body, key()), 409, "VERSION_CONFLICT");
            var startKey = key();
            var started = data(winner.post(detail + "/start", start, startKey), 200);
            assertThat(started.get("status").asString()).isEqualTo("IN_PROGRESS");
            assertThat(started.get("version").asInt()).isEqualTo(version + 2);
            assertThat(started.get("allowedActions").toString()).doesNotContain("START");
            var newAttempt = json.createObjectNode().put("expectedVersion", version + 2);
            assertError(winner.post(detail + "/start", newAttempt, key()), 409, "STATE_CONFLICT");
            var claimReplay = winner.post(detail + "/claim", body, winnerKey);
            assertThat(data(claimReplay, 200)).isEqualTo(claimed);
            assertThat(claimReplay.body().get("replayed").asBoolean()).isTrue();
            var startReplay = winner.post(detail + "/start", start, startKey);
            assertThat(data(startReplay, 200)).isEqualTo(started);
            assertThat(startReplay.body().get("replayed").asBoolean()).isTrue();
            assertThat(data(observer.get(detail), 200).get("status").asString()).isEqualTo("IN_PROGRESS");
            assertThat(jdbc.queryForList("""
                    SELECT a.event_type FROM activity_event a
                    JOIN command_receipt r ON r.id = a.command_id AND r.project_id = a.project_id
                    WHERE a.entity_type = 'TASK' AND a.entity_id = ?
                      AND a.event_type IN ('TASK_CLAIMED', 'TASK_STARTED') ORDER BY a.id
                    """, String.class, Long.parseLong(taskId)))
                    .containsExactly("TASK_CLAIMED", "TASK_STARTED");
        }
    }

    @Test
    void claimAndManualAssignmentDoNotOverwriteEachOther() throws Exception {
        try (var owner = createAccount(); var claimant = createAccount()) {
            var space = data(owner.post("/workspaces", json.createObjectNode().put("name", "分派竞争"), key()), 201);
            var workspacePath = "/workspaces/" + space.get("id").asString();
            var project = data(owner.post(workspacePath + "/projects",
                    json.createObjectNode().put("name", "团队邀请").put("goal", "准备交付"), key()), 201);
            var projectPath = "/projects/" + project.get("id").asString();
            var claimantId = addProjectMember(owner, workspacePath, projectPath, claimant, "CONTRIBUTOR");
            var ownerId = data(owner.get(projectPath + "/members"), 200).get("items").valueStream()
                    .filter(member -> "OWNER".equals(member.get("role").asString())).findFirst().orElseThrow()
                    .get("id").asString();
            var ready = newReadyTask(owner, projectPath);
            var detail = projectPath + "/tasks/" + ready.get("id").asString();
            var version = ready.get("version").asInt();
            var body = json.createObjectNode().put("expectedVersion", version);
            var claim = claimant.commandRequest("POST", detail + "/claim", body, key());
            var assignment = owner.commandRequest("PUT", detail + "/assignment",
                    body.deepCopy().put("assigneeMemberId", ownerId).putNull("reviewerMemberId"), key());
            var results = race(() -> claimant.send(claim), () -> owner.send(assignment));
            assertThat(results.stream().map(Response::status).toList()).containsExactlyInAnyOrder(200, 409);
            boolean claimWon = results.get(0).status() == 200;
            if (claimWon) {
                assertError(results.get(1), 409, "VERSION_CONFLICT");
            } else {
                assertError(results.get(0), 409, "ALREADY_ASSIGNED");
            }
            var finalTask = data(owner.get(detail), 200);
            assertThat(finalTask.get("assigneeMemberId").asString()).isEqualTo(claimWon ? claimantId : ownerId);
            assertThat(finalTask.get("version").asInt()).isEqualTo(version + 1);
            assertThat(finalTask.get("status").asString()).isEqualTo("READY");
        }
    }

    @Test
    void reviewsTheCurrentRoundAndKeepsEarlierMaterialsAndReviewerSnapshots() throws Exception {
        try (var owner = createAccount(); var executor = createAccount(); var replacement = createAccount();
                var reader = createAccount()) {
            var project = reviewProject(owner, executor);
            var replacementId = addProjectMember(owner, project.workspacePath(), project.projectPath(),
                    replacement, "CONTRIBUTOR");
            addProjectMember(owner, project.workspacePath(), project.projectPath(), reader, "OBSERVER");
            var active = startReviewTask(project, owner, executor);
            var taskPath = project.projectPath() + "/tasks/" + active.get("id").asString();
            var submissions = taskPath + "/submissions";
            var requestKey = key();
            var firstRequest = submissionInput(taskVersion(active), "第一轮材料");
            var unsupported = submissionInput(taskVersion(active), "不安全的材料");
            unsupported.putArray("materials").addObject().put("kind", "LINK").put("label", "链接")
                    .put("url", "javascript:alert(1)");
            assertError(executor.post(submissions, unsupported, key()), 422, "PRECONDITION_FAILED");
            assertError(owner.post(submissions, firstRequest, key()), 403, "FORBIDDEN");
            var first = data(executor.post(submissions, firstRequest, requestKey), 201);
            var replay = executor.post(submissions, firstRequest, requestKey);
            assertThat(data(replay, 201)).isEqualTo(first);
            assertThat(replay.body().get("replayed").asBoolean()).isTrue();
            var firstSubmission = first.get("submission");
            var firstId = firstSubmission.get("id").asString();
            assertThat(first.get("task").get("currentSubmissionId").asString()).isEqualTo(firstId);
            assertThat(firstSubmission.get("scopeSnapshot").get("acceptanceCriteria").size()).isEqualTo(1);
            assertThat(firstSubmission.get("materials").get(1).get("url").asString()).isEqualTo("https://example.com/invite");
            int firstVersion = taskVersion(first.get("task"));

            assertError(reader.post(submissions + "/" + firstId + "/approve", versionBody(firstVersion), key()),
                    403, "FORBIDDEN");
            assertError(owner.post(submissions + "/" + firstId + "/return", versionBody(firstVersion), key()),
                    400, "INVALID_REQUEST");
            var blank = owner.post(submissions + "/" + firstId + "/return", versionBody(firstVersion).put("comment", " "), key());
            assertError(blank, 422, "PRECONDITION_FAILED");
            assertThat(blank.body().get("error").get("details").get("violations").get(0).get("reason").asString())
                    .isEqualTo("COMMENT_REQUIRED");
            var returned = data(owner.post(submissions + "/" + firstId + "/return",
                    versionBody(firstVersion).put("comment", "请补充过期链接的验证"), key()), 200);
            assertThat(returned.get("task").get("status").asString()).isEqualTo("IN_PROGRESS");

            var second = data(executor.post(submissions,
                    submissionInput(taskVersion(returned.get("task")), "第二轮材料"), key()), 201);
            var secondId = second.get("submission").get("id").asString();
            int secondVersion = taskVersion(second.get("task"));
            var old = owner.post(submissions + "/" + firstId + "/approve", versionBody(firstVersion), key());
            assertError(old, 409, "SUBMISSION_STALE");
            assertThat(old.body().get("error").get("details").get("currentSubmissionId").asString()).isEqualTo(secondId);

            var reassigned = data(owner.write("PUT", taskPath + "/assignment", versionBody(secondVersion)
                    .put("assigneeMemberId", project.executorId()).put("reviewerMemberId", replacementId)
                    .put("reason", "更换验收人"), key()), 200);
            assertThat(reassigned.get("currentSubmissionId").asString()).isEqualTo(secondId);
            assertError(owner.post(submissions + "/" + secondId + "/approve", versionBody(taskVersion(reassigned)), key()),
                    403, "FORBIDDEN");
            assertError(replacement.post(submissions + "/" + secondId + "/approve", versionBody(secondVersion), key()),
                    409, "VERSION_CONFLICT");
            var approveKey = key();
            var approve = versionBody(taskVersion(reassigned));
            var done = data(replacement.post(submissions + "/" + secondId + "/approve", approve, approveKey), 200);
            assertThat(done.get("task").get("status").asString()).isEqualTo("DONE");
            assertThat(done.get("task").get("currentSubmissionId").isNull()).isTrue();
            assertThat(done.get("submission").get("reviewerSnapshotMemberId").asString()).isEqualTo(project.ownerId());
            assertThat(done.get("decision").get("reviewerMemberId").asString()).isEqualTo(replacementId);
            var approvalReplay = replacement.post(submissions + "/" + secondId + "/approve", approve, approveKey);
            assertThat(data(approvalReplay, 200)).isEqualTo(done);
            assertThat(approvalReplay.body().get("replayed").asBoolean()).isTrue();

            var previous = data(reader.get(submissions + "/" + firstId), 200);
            assertThat(previous.get("status").asString()).isEqualTo("RETURNED");
            assertThat(previous.get("decision").get("comment").asString()).isEqualTo("请补充过期链接的验证");
            assertThat(previous.get("materials").get(0).get("body").asString()).isEqualTo("第一轮材料");
            assertThat(data(reader.get(submissions), 200).get("total").asInt()).isEqualTo(2);
        }
    }

    @Test
    void approvalAndWithdrawalCanOnlyFinishTheSameSubmissionOnce() throws Exception {
        try (var owner = createAccount(); var executor = createAccount(); var successor = createAccount()) {
            var project = reviewProject(owner, executor);
            var task = startReviewTask(project, owner, executor);
            var base = project.projectPath() + "/tasks/" + task.get("id").asString();
            var first = data(executor.post(base + "/submissions", submissionInput(taskVersion(task), "待确认成果"), key()), 201);
            var id = first.get("submission").get("id").asString();
            var detail = base + "/submissions/" + id;
            var body = versionBody(taskVersion(first.get("task")));
            var accept = owner.commandRequest("POST", detail + "/approve", body, key());
            var withdraw = executor.commandRequest("POST", detail + "/withdraw", body.deepCopy()
                    .put("reason", "需要补充材料"), key());
            var results = race(() -> owner.send(accept), () -> executor.send(withdraw));
            assertThat(results.stream().map(Response::status).toList()).containsExactlyInAnyOrder(200, 409);
            boolean approved = results.get(0).status() == 200;
            assertError(results.get(approved ? 1 : 0), 409, "SUBMISSION_STALE");
            var finalSubmission = data(executor.get(detail), 200);
            var finalTask = data(executor.get(base), 200);
            assertThat(finalTask.get("currentSubmissionId").isNull()).isTrue();
            assertThat(taskVersion(finalTask)).isEqualTo(taskVersion(first.get("task")) + 1);
            assertThat(finalTask.get("status").asString()).isEqualTo(approved ? "DONE" : "IN_PROGRESS");
            assertThat(finalSubmission.get("status").asString()).isEqualTo(approved ? "APPROVED" : "WITHDRAWN");
            assertThat(finalSubmission.get("decision").isNull()).isEqualTo(!approved);
            if (!approved) {
                assertThat(finalSubmission.get("endReason").asString()).isEqualTo("需要补充材料");
            }

            var another = startReviewTask(project, owner, executor);
            var newTaskPath = project.projectPath() + "/tasks/" + another.get("id").asString();
            var anotherPath = newTaskPath + "/submissions";
            var submitKey = key();
            var submission = submissionInput(taskVersion(another), "管理撤回");
            var pending = data(executor.post(anotherPath, submission, submitKey), 201);
            var pendingPath = anotherPath + "/" + pending.get("submission").get("id").asString();
            var pendingVersion = taskVersion(pending.get("task"));
            var memberPath = project.projectPath() + "/members/" + project.executorId();
            var responsibilities = data(executor.get(memberPath + "/responsibilities"), 200).get("items");
            assertThat(responsibilities.valueStream().anyMatch(duty ->
                    duty.get("taskId").asString().equals(another.get("id").asString()) && duty.get("assignee").asBoolean()))
                    .isTrue();
            var removal = versionBody(1).put("reason", "成员离开团队").put("mode", "REQUIRE_CLEAR");
            assertError(owner.post(memberPath + "/remove", removal, key()), 422, "PRECONDITION_FAILED");
            var revoked = data(owner.post(memberPath + "/remove", removal.put("mode", "REVOKE_NOW"), key()), 200);
            assertThat(revoked.get("status").asString()).isEqualTo("INACTIVE");
            assertThat(revoked.get("version").asInt()).isEqualTo(2);
            data(executor.get("/me"), 200);
            assertError(executor.get(pendingPath), 404, "NOT_FOUND");
            assertError(executor.get("/commands/" + submitKey), 404, "NOT_FOUND");
            assertError(executor.post(anotherPath, submission, submitKey), 404, "NOT_FOUND");

            var blocked = owner.post(pendingPath + "/approve", versionBody(pendingVersion), key());
            assertError(blocked, 422, "PRECONDITION_FAILED");
            assertThat(blocked.body().get("error").get("details").get("violations").get(0).get("reason").asString())
                    .isEqualTo("ASSIGNEE_INACTIVE");
            var managed = data(owner.post(pendingPath + "/withdraw", versionBody(pendingVersion)
                    .put("reason", "执行者已失效，重新安排交付"), key()), 200);
            assertThat(managed.get("submission").get("status").asString()).isEqualTo("WITHDRAWN");
            assertThat(managed.get("submission").get("endedByMemberId").asString()).isEqualTo(project.ownerId());
            assertThat(managed.get("submission").get("decision").isNull()).isTrue();
            assertThat(managed.get("submission").get("assigneeSnapshotMemberId").asString()).isEqualTo(project.executorId());

            var successorId = addProjectMember(owner, project.workspacePath(), project.projectPath(), successor, "CONTRIBUTOR");
            var reassigned = data(owner.write("PUT", newTaskPath + "/assignment", versionBody(taskVersion(managed.get("task")))
                    .put("assigneeMemberId", successorId).put("reviewerMemberId", project.ownerId())
                    .put("reason", "将进行中的工作移交给接任者"), key()), 200);
            var resubmitted = data(successor.post(anotherPath, submissionInput(taskVersion(reassigned), "接任者重新提交"), key()), 201);
            assertThat(resubmitted.get("submission").get("submittedByMemberId").asString()).isEqualTo(successorId);
            assertThat(resubmitted.get("submission").get("submissionNo").asInt()).isEqualTo(2);
        }
    }

    @Test
    void downgradingAReviewerKeepsReadAccessAndAllowsAReplacementToFinish() throws Exception {
        try (var owner = createAccount(); var executor = createAccount(); var reviewer = createAccount()) {
            var project = reviewProject(owner, executor);
            var reviewerId = addProjectMember(owner, project.workspacePath(), project.projectPath(), reviewer, "CONTRIBUTOR");
            var active = startReviewTask(project, owner, executor);
            var taskPath = project.projectPath() + "/tasks/" + active.get("id").asString();
            var assigned = data(owner.write("PUT", taskPath + "/assignment", versionBody(taskVersion(active))
                    .put("assigneeMemberId", project.executorId()).put("reviewerMemberId", reviewerId), key()), 200);
            var pending = data(executor.post(taskPath + "/submissions",
                    submissionInput(taskVersion(assigned), "等待确认"), key()), 201);
            var submissionPath = taskPath + "/submissions/" + pending.get("submission").get("id").asString();
            var memberPath = project.projectPath() + "/members/" + reviewerId;
            var downgrade = versionBody(1).put("role", "OBSERVER").put("reason", "调整确认职责");
            assertError(reviewer.write("PATCH", memberPath, downgrade, key()), 403, "FORBIDDEN");
            var observer = data(owner.write("PATCH", memberPath, downgrade, key()), 200);
            assertThat(observer.get("effective").asBoolean()).isTrue();
            assertThat(observer.get("role").asString()).isEqualTo("OBSERVER");
            assertError(owner.write("PATCH", memberPath, downgrade, key()), 409, "VERSION_CONFLICT");

            data(reviewer.get(submissionPath), 200);
            assertError(reviewer.post(submissionPath + "/approve", versionBody(taskVersion(pending.get("task"))), key()),
                    403, "FORBIDDEN");
            var reassigned = data(owner.write("PUT", taskPath + "/assignment", versionBody(taskVersion(pending.get("task")))
                    .put("assigneeMemberId", project.executorId()).put("reviewerMemberId", project.ownerId()), key()), 200);
            var done = data(owner.post(submissionPath + "/approve", versionBody(taskVersion(reassigned)), key()), 200);
            assertThat(done.get("submission").get("reviewerSnapshotMemberId").asString()).isEqualTo(reviewerId);
            assertThat(done.get("decision").get("reviewerMemberId").asString()).isEqualTo(project.ownerId());
        }
    }

    @Test
    void workspaceRevocationInvalidatesAllProjectsWithoutExposingTheirTasksOrRestoringAccessOnRejoin() throws Exception {
        try (var owner = createAccount(); var executor = createAccount(); var spaceAdmin = createAccount()) {
            var project = reviewProject(owner, executor);
            var admin = data(owner.post(project.workspacePath() + "/members", json.createObjectNode()
                    .put("loginName", spaceAdmin.login()).put("role", "ADMIN"), key()), 201);
            var workspaceMembers = data(spaceAdmin.get(project.workspacePath() + "/members"), 200).get("items");
            var ownerSpaceId = workspaceMembers.valueStream()
                    .filter(member -> owner.login().equals(member.get("user").get("loginName").asString()))
                    .findFirst().orElseThrow().get("id").asString();
            var executorSpaceId = workspaceMembers.valueStream()
                    .filter(member -> executor.login().equals(member.get("user").get("loginName").asString()))
                    .findFirst().orElseThrow().get("id").asString();
            var second = data(owner.post(project.workspacePath() + "/projects",
                    json.createObjectNode().put("name", "另一个项目").put("goal", "验证空间撤权"), key()), 201);
            var secondPath = "/projects/" + second.get("id").asString();
            data(owner.post(secondPath + "/members", json.createObjectNode()
                    .put("workspaceMemberId", executorSpaceId).put("role", "CONTRIBUTOR"), key()), 201);
            var active = startReviewTask(project, owner, executor);
            var taskPath = project.projectPath() + "/tasks/" + active.get("id").asString();
            assertError(spaceAdmin.get(taskPath), 404, "NOT_FOUND");

            var ownerRemoval = project.workspacePath() + "/members/" + ownerSpaceId + "/remove";
            var protectedOwner = spaceAdmin.post(ownerRemoval,
                    versionBody(1).put("reason", "移交前离开").put("mode", "REVOKE_NOW"), key());
            assertError(protectedOwner, 422, "LAST_OWNER_REQUIRED");
            assertThat(protectedOwner.body().get("error").get("details").get("projectIds").size()).isEqualTo(2);
            var executorRemoval = project.workspacePath() + "/members/" + executorSpaceId + "/remove";
            var blocked = spaceAdmin.post(executorRemoval, versionBody(1).put("reason", "离开团队"), key());
            assertError(blocked, 422, "PRECONDITION_FAILED");
            assertThat(blocked.body().get("error").get("details").get("projectIds").get(0).asString())
                    .isEqualTo(Long.toString(project.projectId()));
            assertThat(blocked.body().toString()).doesNotContain("实现邀请", "另一个项目");
            var removed = data(spaceAdmin.post(executorRemoval,
                    versionBody(1).put("reason", "立即收回访问").put("mode", "REVOKE_NOW"), key()), 200);
            assertThat(removed.get("status").asString()).isEqualTo("INACTIVE");
            assertError(executor.get(taskPath), 404, "NOT_FOUND");
            assertError(executor.get(secondPath), 404, "NOT_FOUND");

            var joined = data(spaceAdmin.post(project.workspacePath() + "/members", json.createObjectNode()
                    .put("loginName", executor.login()).put("role", "MEMBER"), key()), 200);
            assertThat(joined.get("id").asString()).isEqualTo(executorSpaceId);
            assertThat(data(executor.get(project.workspacePath() + "/projects"), 200).get("total").asInt()).isZero();
            var restored = data(owner.post(project.projectPath() + "/members", json.createObjectNode()
                    .put("workspaceMemberId", executorSpaceId).put("role", "CONTRIBUTOR"), key()), 200);
            assertThat(restored.get("id").asString()).isEqualTo(project.executorId());
            data(executor.get(taskPath), 200);
            assertError(executor.get(secondPath), 404, "NOT_FOUND");

            var demotion = versionBody(1).put("role", "MEMBER");
            data(spaceAdmin.write("PATCH", project.workspacePath() + "/members/" + ownerSpaceId, demotion, key()), 200);
            assertError(spaceAdmin.write("PATCH", project.workspacePath() + "/members/" + admin.get("id").asString(),
                    demotion, key()), 422, "PRECONDITION_FAILED");
            assertThat(data(owner.get(project.projectPath()), 200).get("currentRole").asString()).isEqualTo("OWNER");
        }
    }

    @Test
    void concurrentOwnerDeparturesKeepOneEffectiveOwner() throws Exception {
        try (var owner = createAccount(); var coOwner = createAccount()) {
            var space = data(owner.post("/workspaces", json.createObjectNode().put("name", "负责人并发验证"), key()), 201);
            var workspacePath = "/workspaces/" + space.get("id").asString();
            var project = data(owner.post(workspacePath + "/projects",
                    json.createObjectNode().put("name", "团队邀请").put("goal", "本期交付"), key()), 201);
            var projectPath = "/projects/" + project.get("id").asString();
            var originalOwner = data(owner.get(projectPath + "/members"), 200).get("items").get(0).get("id").asString();
            var secondOwner = addProjectMember(owner, workspacePath, projectPath, coOwner, "OWNER");
            var departure = versionBody(1).put("reason", "结束我的参与").put("mode", "REVOKE_NOW");
            var first = owner.commandRequest("POST", projectPath + "/members/" + originalOwner + "/remove", departure, key());
            var second = coOwner.commandRequest("POST", projectPath + "/members/" + secondOwner + "/remove", departure, key());
            var results = race(() -> owner.send(first), () -> coOwner.send(second));
            assertThat(results.stream().map(Response::status).toList()).containsExactlyInAnyOrder(200, 422);
            boolean firstLeft = results.get(0).status() == 200;
            assertError(results.get(firstLeft ? 1 : 0), 422, "LAST_OWNER_REQUIRED");
            var remaining = firstLeft ? coOwner : owner;
            var departed = firstLeft ? owner : coOwner;
            var members = data(remaining.get(projectPath + "/members"), 200).get("items");
            assertThat(members.valueStream().filter(member -> member.get("effective").asBoolean()).count()).isEqualTo(1);
            assertError(departed.get(projectPath), 404, "NOT_FOUND");
        }
    }

    @Test
    void revocationAndApprovalRespectTheSameProjectLock() throws Exception {
        try (var owner = createAccount(); var executor = createAccount(); var reviewer = createAccount()) {
            var project = reviewProject(owner, executor);
            var reviewerId = addProjectMember(owner, project.workspacePath(), project.projectPath(), reviewer, "CONTRIBUTOR");
            var active = startReviewTask(project, owner, executor);
            var taskPath = project.projectPath() + "/tasks/" + active.get("id").asString();
            var assigned = data(owner.write("PUT", taskPath + "/assignment", versionBody(taskVersion(active))
                    .put("assigneeMemberId", project.executorId()).put("reviewerMemberId", reviewerId), key()), 200);
            var pending = data(executor.post(taskPath + "/submissions",
                    submissionInput(taskVersion(assigned), "等待确认"), key()), 201);
            var submissionPath = taskPath + "/submissions/" + pending.get("submission").get("id").asString();
            var approveKey = key();
            var approve = reviewer.commandRequest("POST", submissionPath + "/approve",
                    versionBody(taskVersion(pending.get("task"))), approveKey);
            var remove = owner.commandRequest("POST", project.projectPath() + "/members/" + reviewerId + "/remove",
                    versionBody(1).put("reason", "撤回对项目的访问").put("mode", "REVOKE_NOW"), key());
            var result = race(() -> reviewer.send(approve), () -> owner.send(remove));
            data(result.get(1), 200);
            var finalSubmission = data(owner.get(submissionPath), 200);
            if (result.get(0).status() == 200) {
                assertThat(finalSubmission.get("decision").get("reviewerMemberId").asString()).isEqualTo(reviewerId);
            } else {
                assertError(result.get(0), 404, "NOT_FOUND");
                assertThat(finalSubmission.get("status").asString()).isEqualTo("PENDING");
                assertThat(finalSubmission.get("decision").isNull()).isTrue();
            }
            assertError(reviewer.get(submissionPath), 404, "NOT_FOUND");
            assertError(reviewer.get("/commands/" + approveKey), 404, "NOT_FOUND");
        }
    }

    @Test
    void failureAfterReceiptAndActivityRollsBackTheDecisionAndCanRetry() throws Exception {
        try (var owner = createAccount(); var executor = createAccount()) {
            var project = reviewProject(owner, executor);
            var task = startReviewTask(project, owner, executor);
            var base = project.projectPath() + "/tasks/" + task.get("id").asString();
            var first = data(executor.post(base + "/submissions", submissionInput(taskVersion(task), "完整材料"), key()), 201);
            var submissionId = first.get("submission").get("id").asString();
            var detail = base + "/submissions/" + submissionId;
            var version = taskVersion(first.get("task"));
            var ackKey = key();
            var body = versionBody(version);
            var pid = project.projectId();
            var activityBefore = countForProject("activity_event", pid);
            var deliveryBefore = countForProject("notification_delivery", pid);
            var receiptsBefore = countForProject("command_receipt", pid);
            deliveryFailure.projectId.set(pid);

            assertThat(owner.post(detail + "/approve", body, ackKey).status()).isEqualTo(500);
            var stillPending = data(executor.get(detail), 200);
            var remaining = data(executor.get(base), 200);
            assertThat(remaining.get("currentSubmissionId").asString()).isEqualTo(submissionId);
            assertThat(taskVersion(remaining)).isEqualTo(version);
            assertThat(stillPending.get("status").asString()).isEqualTo("PENDING");
            assertThat(stillPending.get("decision").isNull()).isTrue();
            assertThat(countForProject("activity_event", pid)).isEqualTo(activityBefore);
            assertThat(countForProject("notification_delivery", pid)).isEqualTo(deliveryBefore);
            assertThat(countForProject("command_receipt", pid)).isEqualTo(receiptsBefore);
            assertError(owner.get("/commands/" + ackKey), 404, "NOT_FOUND");

            data(owner.post(detail + "/approve", body, ackKey), 200);
            assertThat(countForProject("submission_decision", pid)).isEqualTo(1);
            assertThat(countForProject("activity_event", pid)).isEqualTo(activityBefore + 1);
            assertThat(countForProject("notification_delivery", pid)).isEqualTo(deliveryBefore + 1);
            assertThat(countForProject("command_receipt", pid)).isEqualTo(receiptsBefore + 1);
        }
    }

    @Test
    void savesOneRevisionFromTheSameBaseAndReplaysTheWinningCommand() throws Exception {
        try (var owner = createAccount(); var editor = createAccount(); var observer = createAccount()) {
            var project = reviewProject(owner, editor);
            addProjectMember(owner, project.workspacePath(), project.projectPath(), observer, "OBSERVER");
            var documents = project.projectPath() + "/documents";
            var initial = "    原始缩进\n\n";
            var create = json.createObjectNode().put("kind", "REQUIREMENT").put("title", "邀请规则")
                    .put("contentMarkdown", initial);
            var first = data(owner.post(documents, create, key()), 201);
            var path = documents + "/" + first.get("id").asString() + "/revisions";
            var firstRevision = first.get("currentRevision").get("id").asString();
            var ownerEdit = documentEdit(first, "负责人修订");
            var editorEdit = documentEdit(first, "协作者修订");
            var ownerKey = key();
            var editorKey = key();
            assertError(observer.post(path, ownerEdit, key()), 403, "FORBIDDEN");
            var ownerRequest = owner.commandRequest("POST", path, ownerEdit, ownerKey);
            var editorRequest = editor.commandRequest("POST", path, editorEdit, editorKey);
            var results = race(() -> owner.send(ownerRequest), () -> editor.send(editorRequest));
            assertThat(results).extracting(Response::status).containsExactlyInAnyOrder(201, 409);
            var ownerWon = results.get(0).status() == 201;
            var winner = data(results.get(ownerWon ? 0 : 1), 201);
            var loser = results.get(ownerWon ? 1 : 0);
            assertError(loser, 409, "VERSION_CONFLICT");
            assertThat(loser.body().at("/error/details/currentVersion").asInt()).isEqualTo(2);

            var replay = ownerWon ? owner.post(path, ownerEdit, ownerKey) : editor.post(path, editorEdit, editorKey);
            assertThat(data(replay, 201)).isEqualTo(winner);
            assertThat(replay.body().get("replayed").asBoolean()).isTrue();
            var staleBase = documentEdit(first, "旧页面").put("expectedVersion", 2);
            assertError(owner.post(path, staleBase, key()), 409, "VERSION_CONFLICT");
            var history = data(observer.get(path + "?pageSize=1"), 200);
            assertThat(history.get("total").asInt()).isEqualTo(2);
            assertThat(history.at("/items/0/id").asString()).isEqualTo(winner.at("/currentRevision/id").asString());
            assertThat(history.at("/items/0/contentMarkdown").isMissingNode()).isTrue();
            var stored = data(observer.get(path + "/" + firstRevision), 200);
            assertThat(stored.get("title").asString()).isEqualTo("邀请规则");
            assertThat(stored.get("contentMarkdown").asString()).isEqualTo(initial);
            assertThat(data(observer.get(documents + "?kind=REQUIREMENT&q=%E4%BF%AE%E8%AE%A2"), 200)
                    .get("total").asInt()).isEqualTo(1);
            assertThat(countForProject("document_revision", project.projectId())).isEqualTo(2);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM activity_event WHERE project_id = ? AND entity_type = 'DOCUMENT'
                    """, Integer.class, project.projectId())).isEqualTo(2);
        }
    }

    @Test
    void keepsTheSubmittedDocumentRevisionAndRejectsCrossProjectMaterials() throws Exception {
        try (var owner = createAccount(); var executor = createAccount(); var outside = createAccount()) {
            var project = reviewProject(owner, executor);
            var other = data(owner.post(project.workspacePath() + "/projects",
                    json.createObjectNode().put("name", "另一个项目").put("goal", "验证隔离"), key()), 201);
            var docs = project.projectPath() + "/documents";
            var create = json.createObjectNode().put("kind", "DELIVERY").put("title", "验收记录")
                    .put("contentMarkdown", "当时的验收内容");
            var document = data(executor.post(docs, create, key()), 201);
            var otherDocument = data(owner.post("/projects/" + other.get("id").asString() + "/documents",
                    create, key()), 201);
            var documentId = document.get("id").asString();
            var revisionId = document.at("/currentRevision/id").asString();
            var revisionPath = docs + "/" + documentId + "/revisions";
            assertError(outside.get(revisionPath + "/" + revisionId), 404, "NOT_FOUND");
            assertError(owner.get(docs + "/" + otherDocument.get("id").asString() + "/revisions/" + revisionId),
                    404, "NOT_FOUND");

            var task = startReviewTask(project, owner, executor);
            var taskPath = project.projectPath() + "/tasks/" + task.get("id").asString();
            var path = taskPath + "/submissions";
            var request = submissionInput(taskVersion(task), "验收材料");
            var material = request.withArray("materials").addObject().put("kind", "DOCUMENT").put("label", "验收记录");
            material.put("documentRevisionId", otherDocument.at("/currentRevision/id").asString());
            assertError(executor.post(path, request, key()), 422, "PRECONDITION_FAILED");
            assertThat(countForProject("task_submission", project.projectId())).isZero();
            assertThat(countForProject("submission_material", project.projectId())).isZero();

            material.put("documentRevisionId", revisionId);
            var submitted = data(executor.post(path, request, key()), 201);
            var submissionId = submitted.at("/submission/id").asString();
            var latest = data(executor.post(revisionPath, documentEdit(document, "更新后的验收记录"), key()), 201);
            assertThat(latest.at("/currentRevision/id").asString()).isNotEqualTo(revisionId);
            var approved = data(owner.post(path + "/" + submissionId + "/approve",
                    versionBody(taskVersion(submitted.get("task"))), key()), 200);
            assertThat(approved.at("/submission/materials/2/documentRevisionId").asString()).isEqualTo(revisionId);
            var stored = data(owner.get(path + "/" + submissionId), 200).at("/materials/2");
            assertThat(stored.get("documentId").asString()).isEqualTo(documentId);
            assertThat(stored.get("revisionNo").asInt()).isEqualTo(1);
            assertThat(data(owner.get(revisionPath + "/" + revisionId), 200).get("contentMarkdown").asString())
                    .isEqualTo("当时的验收内容");
        }
    }

    private ObjectNode documentEdit(JsonNode base, String title) {
        return versionBody(base.get("version").asInt()).put("baseRevisionId", base.at("/currentRevision/id").asString())
                .put("title", title).put("contentMarkdown", title + "的正文").put("changeNote", "更新内容");
    }

    private ReviewProject reviewProject(Client owner, Client executor) throws Exception {
        var space = data(owner.post("/workspaces", json.createObjectNode().put("name", "成果流程"), key()), 201);
        var workspacePath = "/workspaces/" + space.get("id").asString();
        var project = data(owner.post(workspacePath + "/projects",
                json.createObjectNode().put("name", "团队邀请").put("goal", "本期交付"), key()), 201);
        var projectPath = "/projects/" + project.get("id").asString();
        var executorId = addProjectMember(owner, workspacePath, projectPath, executor, "CONTRIBUTOR");
        var ownerId = data(owner.get(projectPath + "/members"), 200).get("items").valueStream()
                .filter(member -> "OWNER".equals(member.get("role").asString())).findFirst().orElseThrow()
                .get("id").asString();
        return new ReviewProject(Long.parseLong(project.get("id").asString()), workspacePath, projectPath, ownerId, executorId);
    }

    private JsonNode startReviewTask(ReviewProject project, Client owner, Client executor) throws Exception {
        var task = newReadyTask(owner, project.projectPath());
        var base = project.projectPath() + "/tasks/" + task.get("id").asString();
        var assigned = data(owner.write("PUT", base + "/assignment", versionBody(taskVersion(task))
                .put("assigneeMemberId", project.executorId()).putNull("reviewerMemberId"), key()), 200);
        var active = data(executor.post(base + "/start", versionBody(taskVersion(assigned)), key()), 200);
        assertError(executor.post(base + "/submissions", submissionInput(taskVersion(active), "材料"), key()),
                422, "PRECONDITION_FAILED");
        return data(owner.write("PUT", base + "/assignment", versionBody(taskVersion(active))
                .put("assigneeMemberId", project.executorId()).put("reviewerMemberId", project.ownerId()), key()), 200);
    }

    private ObjectNode submissionInput(int version, String material) {
        var input = versionBody(version).put("summary", "团队邀请成果");
        var items = input.putArray("materials");
        items.addObject().put("kind", "TEXT").put("label", "验证记录").put("body", material);
        items.addObject().put("kind", "LINK").put("label", "演示地址").put("url", "https://example.com/invite");
        return input;
    }

    private ObjectNode versionBody(int version) {
        return json.createObjectNode().put("expectedVersion", version);
    }

    private int taskVersion(JsonNode task) {
        return task.get("version").asInt();
    }

    private int countForProject(String table, long projectId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE project_id = ?", Integer.class, projectId);
    }

    private record ReviewProject(long projectId, String workspacePath, String projectPath, String ownerId, String executorId) {
    }

    @TestConfiguration
    static class SubmissionFailureConfiguration {
        @Bean
        FailDeliveryOnce failDeliveryOnce() {
            return new FailDeliveryOnce();
        }
    }

    @Intercepts(@Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}))
    static class FailDeliveryOnce implements Interceptor {
        final AtomicLong projectId = new AtomicLong();

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            var statement = (MappedStatement) invocation.getArgs()[0];
            if (statement.getId().endsWith("SubmissionMapper.enqueue")
                    && invocation.getArgs()[1] instanceof Map<?, ?> parameters
                    && parameters.get("projectId") instanceof Long project && projectId.compareAndSet(project, 0L)) {
                throw new IllegalStateException("Injected notification_delivery write failure");
            }
            return invocation.proceed();
        }
    }

    private JsonNode newReadyTask(Client owner, String projectPath) throws Exception {
        var input = json.createObjectNode().put("title", "实现邀请")
                .put("goal", "允许发出邀请").put("expectedOutcome", "成员可接受有效邀请");
        input.putArray("acceptanceCriteria").addObject().put("key", "ac1").put("text", "成员成功加入");
        var created = data(owner.post(projectPath + "/tasks", input, key()), 201);
        return data(owner.post(projectPath + "/tasks/" + created.get("id").asString() + "/ready",
                json.createObjectNode().put("expectedVersion", created.get("version").asInt()), key()), 200);
    }

    private List<Response> race(Callable<Response> first, Callable<Response> second) throws Exception {
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var left = executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return first.call();
            });
            var right = executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return second.call();
            });
            return List.of(left.get(), right.get());
        }
    }

    private String addProjectMember(Client owner, String workspacePath, String projectPath, Client target, String role)
            throws Exception {
        var member = data(owner.post(workspacePath + "/members",
                json.createObjectNode().put("loginName", target.login()).put("role", "MEMBER"), key()), 201);
        return data(owner.post(projectPath + "/members", json.createObjectNode()
                .put("workspaceMemberId", member.get("id").asString()).put("role", role), key()), 201).get("id").asString();
    }

    private Client createAccount() throws Exception {
        var login = "scope-test-" + UUID.randomUUID();
        var password = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO app_user(login_name, display_name, password_hash, status)
                VALUES (?, ?, ?, 'ACTIVE')
                """, login, "目录测试用户", passwords.encode(password));
        testUsers.add(login);
        return signIn(login, password);
    }

    private Client signIn(String loginName, String password) throws Exception {
        var browser = new Client(loginName, password);
        data(browser.post("/auth/login", json.createObjectNode().put("loginName", loginName)
                .put("password", password), null), 200);
        return browser;
    }

    private String key() {
        return UUID.randomUUID().toString();
    }

    private JsonNode data(Response response, int status) {
        assertThat(response.status()).as("body: %s", response.body()).isEqualTo(status);
        return response.body().get("data");
    }

    private void assertError(Response response, int status, String code) {
        assertThat(response.status()).as("body: %s", response.body()).isEqualTo(status);
        assertThat(response.body().get("error").get("code").asString()).isEqualTo(code);
    }

    private record Response(int status, JsonNode body) {
    }

    private final class Client implements AutoCloseable {
        private final String login;
        private final String password;
        private final HttpClient http = HttpClient.newBuilder()
                .proxy(ProxySelector.of(null))
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .connectTimeout(Duration.ofSeconds(4))
                .build();

        Client(String login, String password) {
            this.login = login;
            this.password = password;
        }

        String login() {
            return login;
        }

        String password() {
            return password;
        }

        Response get(String path) throws Exception {
            return send(request(path).GET());
        }

        Response post(String path, JsonNode body, String key) throws Exception {
            return write("POST", path, body, key);
        }

        Response write(String method, String path, JsonNode body, String key) throws Exception {
            return send(commandRequest(method, path, body, key));
        }

        private HttpRequest.Builder commandRequest(String method, String path, JsonNode body, String key) throws Exception {
            var csrf = data(get("/auth/csrf"), 200);
            var builder = request(path).header("Content-Type", "application/json")
                    .header(csrf.get("headerName").asString(), csrf.get("token").asString());
            if (key != null) {
                builder.header("Idempotency-Key", key);
            }
            return builder.method(method, HttpRequest.BodyPublishers.ofString(body.toString()));
        }

        private HttpRequest.Builder request(String path) {
            return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
                    .timeout(Duration.ofSeconds(6));
        }

        private Response send(HttpRequest.Builder request) throws Exception {
            var result = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(result.statusCode(), json.readTree(result.body()));
        }

        @Override
        public void close() {
            http.close();
        }
    }
}
