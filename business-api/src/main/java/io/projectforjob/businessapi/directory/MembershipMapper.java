package io.projectforjob.businessapi.directory;

import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
interface MembershipMapper {

    @Select("""
            SELECT COUNT(*) FROM workspace_member wm JOIN app_user u ON u.id = wm.user_id
            WHERE wm.workspace_id = #{workspaceId} AND wm.id <> #{memberId}
              AND wm.role = 'ADMIN' AND wm.status = 'ACTIVE' AND u.status = 'ACTIVE'
            """)
    int otherActiveAdmins(long workspaceId, long memberId);

    @Select("""
            SELECT COUNT(*) FROM project_member pm JOIN workspace_member wm ON wm.id = pm.workspace_member_id
            JOIN app_user u ON u.id = wm.user_id
            WHERE pm.project_id = #{projectId} AND pm.id <> #{memberId} AND pm.role = 'OWNER'
              AND pm.status = 'ACTIVE' AND wm.status = 'ACTIVE' AND u.status = 'ACTIVE'
            """)
    int otherActiveOwners(long projectId, long memberId);

    @Select("""
            SELECT project_id, id, role FROM project_member
            WHERE workspace_id = #{workspaceId} AND workspace_member_id = #{memberId} AND status = 'ACTIVE'
            ORDER BY project_id
            """)
    List<AffectedProject> activeProjects(long workspaceId, long memberId);

    @Select("""
            SELECT id, title, status, (assignee_member_id = #{memberId}) IS TRUE AS assignee,
                   (reviewer_member_id = #{memberId}) IS TRUE AS reviewer
            FROM task WHERE project_id = #{projectId} AND status IN ('DRAFT','READY','IN_PROGRESS','IN_REVIEW')
                AND (assignee_member_id = #{memberId} OR reviewer_member_id = #{memberId})
            ORDER BY id
            """)
    List<ResponsibilityRow> openResponsibilities(long projectId, long memberId);

    @Select("""
            SELECT EXISTS (
                SELECT 1 FROM task WHERE project_id = #{projectId}
                  AND status IN ('DRAFT','READY','IN_PROGRESS','IN_REVIEW')
                  AND (assignee_member_id = #{memberId} OR reviewer_member_id = #{memberId})
            )
            """)
    boolean hasOpenResponsibilities(long projectId, long memberId);

    @Update("""
            UPDATE workspace_member SET role = #{role}, version = version + 1, updated_at = CURRENT_TIMESTAMP(3)
            WHERE workspace_id = #{workspaceId} AND id = #{memberId} AND version = #{version} AND status = 'ACTIVE'
            """)
    int changeWorkspaceRole(long workspaceId, long memberId, int version, String role);

    @Update("""
            UPDATE project_member SET role = #{role}, version = version + 1, updated_at = CURRENT_TIMESTAMP(3)
            WHERE project_id = #{projectId} AND id = #{memberId} AND version = #{version} AND status = 'ACTIVE'
            """)
    int changeProjectRole(long projectId, long memberId, int version, String role);

    @Update("""
            UPDATE project_member SET status = 'INACTIVE', version = version + 1, updated_at = CURRENT_TIMESTAMP(3)
            WHERE project_id = #{projectId} AND id = #{memberId} AND version = #{version} AND status = 'ACTIVE'
            """)
    int removeProjectMember(long projectId, long memberId, int version);

    @Update("""
            UPDATE workspace_member SET status = 'INACTIVE', version = version + 1, updated_at = CURRENT_TIMESTAMP(3)
            WHERE workspace_id = #{workspaceId} AND id = #{memberId} AND version = #{version} AND status = 'ACTIVE'
            """)
    int removeWorkspaceMember(long workspaceId, long memberId, int version);

    @Update("""
            UPDATE project_member SET status = 'INACTIVE', version = version + 1, updated_at = CURRENT_TIMESTAMP(3)
            WHERE workspace_id = #{workspaceId} AND workspace_member_id = #{memberId} AND status = 'ACTIVE'
            """)
    int removeWorkspaceProjectMemberships(long workspaceId, long memberId);

    @Insert("""
            INSERT INTO activity_event
                (project_id, actor_user_id, actor_member_id, event_type, entity_type, entity_id, command_id, payload)
            VALUES (#{projectId}, #{actorUserId}, #{actorMemberId,jdbcType=BIGINT},
                #{type}, 'PROJECT_MEMBER', #{memberId}, #{commandId}, #{payload})
            """)
    void recordChange(long projectId, long actorUserId, Long actorMemberId, String type, long memberId,
            long commandId, String payload);

    record AffectedProject(long projectId, long memberId, String role) {
    }

    record ResponsibilityRow(long id, String title, String status, boolean assignee, boolean reviewer) {
        Responsibility view() {
            return new Responsibility(Long.toString(id), title, status, assignee, reviewer);
        }
    }

    record Responsibility(String taskId, String title, String status, boolean assignee, boolean reviewer) {
    }

    record Responsibilities(String memberId, String role, boolean effective, boolean lastEffectiveOwner,
            List<Responsibility> items) {
    }
}
