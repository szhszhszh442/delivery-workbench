package io.projectforjob.businessapi.task;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import io.projectforjob.businessapi.api.InsertId;
import io.projectforjob.businessapi.task.SubmissionData.DecisionRow;
import io.projectforjob.businessapi.task.SubmissionData.MaterialInput;
import io.projectforjob.businessapi.task.SubmissionData.MaterialRow;
import io.projectforjob.businessapi.task.SubmissionData.Row;
import io.projectforjob.businessapi.task.SubmissionData.SummaryRow;

@Mapper
interface SubmissionMapper {

    @Select("""
            SELECT id, task_id, submission_no, status, summary, created_at
            FROM task_submission WHERE project_id = #{projectId} AND task_id = #{taskId}
            ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}
            """)
    List<SummaryRow> list(long projectId, long taskId, int limit, long offset);

    @Select("SELECT COUNT(*) FROM task_submission WHERE project_id = #{projectId} AND task_id = #{taskId}")
    long count(long projectId, long taskId);

    @Select("""
            SELECT id, task_id, submission_no, submitted_by_member_id, assignee_snapshot_member_id,
                   reviewer_snapshot_member_id, task_version_at_submit, scope_snapshot, summary, status,
                   ended_at, ended_by_member_id, end_reason, created_at
            FROM task_submission WHERE project_id = #{projectId} AND task_id = #{taskId} AND id = #{submissionId}
            """)
    Row find(long projectId, long taskId, long submissionId);

    @Select("""
            SELECT m.id, m.kind, m.label, m.body, m.url, m.position, m.document_revision_id, r.document_id, r.revision_no
            FROM submission_material m
            LEFT JOIN document_revision r ON r.project_id = m.project_id AND r.id = m.document_revision_id
            WHERE m.project_id = #{projectId} AND m.submission_id = #{submissionId}
            ORDER BY m.position, m.id
            """)
    List<MaterialRow> materials(long projectId, long submissionId);

    @Select("""
            SELECT reviewer_member_id, decision, comment, created_at FROM submission_decision
            WHERE project_id = #{projectId} AND submission_id = #{submissionId}
            """)
    DecisionRow decision(long projectId, long submissionId);

    @Insert("""
            INSERT INTO task_submission
                (project_id, task_id, submission_no, submitted_by_member_id, assignee_snapshot_member_id,
                 reviewer_snapshot_member_id, task_version_at_submit, scope_snapshot, summary, status)
            VALUES (#{projectId}, #{taskId}, #{sequence}, #{memberId}, #{memberId}, #{reviewer},
                #{version}, #{scope}, #{summary}, 'PENDING')
            """)
    @Options(useGeneratedKeys = true, keyProperty = "key.id")
    void create(long projectId, long taskId, int sequence, long memberId, long reviewer, int version,
            String scope, String summary, InsertId key);

    @Insert("""
            INSERT INTO submission_material (project_id, submission_id, kind, label, body, url, document_revision_id, position)
            VALUES (#{projectId}, #{submissionId}, #{material.kind}, #{material.label},
                #{material.body,jdbcType=LONGVARCHAR}, #{material.url,jdbcType=VARCHAR},
                #{material.documentRevisionId,jdbcType=BIGINT}, #{position})
            """)
    void addMaterial(long projectId, long submissionId, MaterialInput material, int position);

    @Insert("""
            INSERT INTO submission_decision (project_id, submission_id, reviewer_member_id, decision, comment, created_at)
            VALUES (#{projectId}, #{submissionId}, #{memberId}, #{decision}, #{comment}, #{now})
            """)
    void recordDecision(long projectId, long submissionId, long memberId, String decision, String comment, LocalDateTime now);

    @Update("""
            UPDATE task
            SET status = 'IN_REVIEW', current_submission_id = #{submissionId},
                submission_seq = #{sequence}, version = version + 1, updated_at = CURRENT_TIMESTAMP(3)
            WHERE project_id = #{projectId} AND id = #{taskId} AND status = 'IN_PROGRESS'
                AND assignee_member_id = #{memberId} AND version = #{version}
            """)
    int enterReview(long projectId, long taskId, long submissionId, int sequence, long memberId, int version);

    @Update("""
            UPDATE task_submission SET status = #{status}, ended_at = #{now}, ended_by_member_id = #{actor},
                end_reason = #{reason,jdbcType=LONGVARCHAR}
            WHERE project_id = #{projectId} AND task_id = #{taskId} AND id = #{submissionId} AND status = 'PENDING'
            """)
    int finishSubmission(long projectId, long taskId, long submissionId, String status, LocalDateTime now, long actor,
            String reason);

    @Update("""
            UPDATE task
            SET status = #{status}, current_submission_id = NULL, version = version + 1, updated_at = #{now}
            WHERE project_id = #{projectId} AND id = #{taskId} AND status = 'IN_REVIEW'
                AND current_submission_id = #{submissionId} AND version = #{version}
                AND (#{reviewer,jdbcType=BIGINT} IS NULL OR reviewer_member_id = #{reviewer,jdbcType=BIGINT})
            """)
    int finishReview(long projectId, long taskId, long submissionId, String status, LocalDateTime now, int version,
            Long reviewer);

    @Insert("""
            INSERT INTO notification_delivery (project_id, event_id, recipient_member_ids, status)
            VALUES (#{projectId}, #{eventId}, #{recipients}, 'PENDING')
            """)
    void enqueue(long projectId, long eventId, String recipients);
}
