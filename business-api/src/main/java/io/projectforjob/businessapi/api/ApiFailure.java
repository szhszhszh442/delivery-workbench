package io.projectforjob.businessapi.api;

import java.util.Map;

import org.springframework.http.HttpStatus;

public final class ApiFailure extends RuntimeException {
    final HttpStatus status;
    final String code;
    final Map<String, ?> details;

    private ApiFailure(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    private ApiFailure(HttpStatus status, String code, String message, Map<String, ?> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public static ApiFailure invalid() {
        return new ApiFailure(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "请检查必填字段、取值和请求标识");
    }

    public static ApiFailure unauthenticated() {
        return new ApiFailure(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录");
    }

    public static ApiFailure forbidden() {
        return new ApiFailure(HttpStatus.FORBIDDEN, "FORBIDDEN", "没有操作权限");
    }

    public static ApiFailure notFound() {
        return new ApiFailure(HttpStatus.NOT_FOUND, "NOT_FOUND", "未找到可访问的对象");
    }

    public static ApiFailure conflict(String message) {
        return new ApiFailure(HttpStatus.CONFLICT, "STATE_CONFLICT", message);
    }

    public static ApiFailure keyReused() {
        return new ApiFailure(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "此请求标识已用于其他操作或内容");
    }

    public static ApiFailure alreadyAssigned(long taskId, long assigneeMemberId, int version) {
        return new ApiFailure(HttpStatus.CONFLICT, "ALREADY_ASSIGNED", "任务已有执行者，请查看最新分派",
                Map.of("resourceId", Long.toString(taskId), "assigneeMemberId", Long.toString(assigneeMemberId),
                        "currentVersion", version));
    }

    public static ApiFailure precondition(String message, Map<String, ?> details) {
        return new ApiFailure(HttpStatus.UNPROCESSABLE_CONTENT, "PRECONDITION_FAILED", message, details);
    }

    public static ApiFailure invalidRelation(String message) {
        return new ApiFailure(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_RELATION", message);
    }

    public static ApiFailure versionConflict(long taskId, int version) {
        return versionConflict("TASK", taskId, version, "任务已被修改，请核对最新内容后再提交");
    }

    public static ApiFailure versionConflict(String resource, long id, int version, String message) {
        return new ApiFailure(HttpStatus.CONFLICT, "VERSION_CONFLICT", message,
                Map.of("resourceType", resource, "resourceId", Long.toString(id), "currentVersion", version));
    }

    public static ApiFailure lastOwnerRequired(Map<String, ?> details) {
        return new ApiFailure(HttpStatus.UNPROCESSABLE_CONTENT, "LAST_OWNER_REQUIRED",
                "项目须保留至少一名有效负责人", details);
    }

    public static ApiFailure submissionStale(long taskId, int version, Long currentSubmissionId) {
        var details = new java.util.LinkedHashMap<String, Object>();
        details.put("resourceId", Long.toString(taskId));
        details.put("currentVersion", version);
        details.put("currentSubmissionId", currentSubmissionId == null ? null : Long.toString(currentSubmissionId));
        return new ApiFailure(HttpStatus.CONFLICT, "SUBMISSION_STALE", "这轮成果已结束或已被替换，请查看当前提交", details);
    }
}
