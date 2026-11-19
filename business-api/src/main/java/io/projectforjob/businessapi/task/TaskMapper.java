package io.projectforjob.businessapi.task;

import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import io.projectforjob.businessapi.api.InsertId;
import io.projectforjob.businessapi.task.TaskData.Filters;
import io.projectforjob.businessapi.task.TaskData.Row;
import io.projectforjob.businessapi.task.TaskData.Scope;
import io.projectforjob.businessapi.task.TaskData.SummaryRow;

@Mapper
interface TaskMapper {

    String FILTERS = """
            FROM task WHERE project_id = #{projectId}
            <if test="filters.status != null">AND status = #{filters.status}</if>
            <if test="filters.assigneeMemberId != null">AND assignee_member_id = #{filters.assigneeMemberId}</if>
            <if test="filters.reviewerMemberId != null">AND reviewer_member_id = #{filters.reviewerMemberId}</if>
            <if test="filters.parentTaskId != null">AND parent_task_id = #{filters.parentTaskId}</if>
            <if test="filters.q != null">AND LOCATE(#{filters.q}, title) > 0</if>
            """;

    @Select("<script>SELECT id, project_id, title, status, creator_member_id, assignee_member_id, reviewer_member_id, version "
            + FILTERS + " ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}</script>")
    List<SummaryRow> list(long projectId, Filters filters, int limit, long offset);

    @Select("<script>SELECT COUNT(*) " + FILTERS + "</script>")
    long count(long projectId, Filters filters);

    @Select("""
            SELECT id, project_id, parent_task_id, title, goal, expected_outcome, acceptance_criteria,
                   open_questions, execution_notes, status, creator_member_id, assignee_member_id,
                   reviewer_member_id, current_submission_id, submission_seq, version, created_at, updated_at
            FROM task WHERE project_id = #{projectId} AND id = #{taskId}
            """)
    Row find(long projectId, long taskId);

    @Insert("""
            INSERT INTO task
                (project_id, title, goal, expected_outcome, acceptance_criteria, open_questions,
                 execution_notes, status, creator_member_id)
            VALUES
                (#{projectId}, #{scope.title}, #{scope.goal}, #{scope.expectedOutcome}, #{criteria}, #{questions},
                 '', 'DRAFT', #{memberId})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "key.id")
    void create(long projectId, long memberId, Scope scope, String criteria, String questions, InsertId key);

    @Update("""
            UPDATE task
            SET title = #{scope.title}, goal = #{scope.goal}, expected_outcome = #{scope.expectedOutcome},
                acceptance_criteria = #{criteria}, open_questions = #{questions}, version = version + 1,
                updated_at = CURRENT_TIMESTAMP(3)
            WHERE project_id = #{projectId} AND id = #{taskId} AND status = #{status} AND version = #{version}
            """)
    int updateScope(long projectId, long taskId, String status, int version, Scope scope, String criteria,
            String questions);

    @Update("""
            UPDATE task SET status = 'READY', version = version + 1, updated_at = CURRENT_TIMESTAMP(3)
            WHERE project_id = #{projectId} AND id = #{taskId} AND status = 'DRAFT' AND version = #{version}
            """)
    int markReady(long projectId, long taskId, int version);

    @Update("""
            UPDATE task SET assignee_member_id = #{memberId},
                version = version + 1, updated_at = CURRENT_TIMESTAMP(3)
            WHERE project_id = #{projectId} AND id = #{taskId} AND status = 'READY'
              AND version = #{version} AND assignee_member_id IS NULL
              AND (reviewer_member_id IS NULL OR reviewer_member_id <> #{memberId})
            """)
    int claim(long projectId, long taskId, int version, long memberId);

    @Update("""
            UPDATE task SET status = 'IN_PROGRESS', version = version + 1, updated_at = CURRENT_TIMESTAMP(3)
            WHERE project_id = #{projectId} AND id = #{taskId} AND status = 'READY'
              AND version = #{version} AND assignee_member_id = #{memberId}
            """)
    int start(long projectId, long taskId, int version, long memberId);

    @Update("""
            UPDATE task SET assignee_member_id = #{assignee,jdbcType=BIGINT},
                reviewer_member_id = #{reviewer,jdbcType=BIGINT},
                version = version + 1, updated_at = CURRENT_TIMESTAMP(3)
            WHERE project_id = #{projectId} AND id = #{taskId}
              AND status IN ('DRAFT','READY','IN_PROGRESS','IN_REVIEW') AND version = #{version}
            """)
    int assign(long projectId, long taskId, int version, Long assignee, Long reviewer);

    @Insert("""
            INSERT INTO activity_event
                (project_id, actor_user_id, actor_member_id, event_type, entity_type, entity_id, command_id, payload)
            VALUES (#{projectId}, #{userId}, #{memberId}, #{type}, 'TASK', #{taskId}, #{commandId}, #{payload})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "eventId.id")
    void recordActivity(long projectId, long userId, long memberId, String type, long taskId, long commandId,
            String payload, InsertId eventId);
}
