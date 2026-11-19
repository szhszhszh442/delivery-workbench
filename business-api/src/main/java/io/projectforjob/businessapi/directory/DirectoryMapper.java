package io.projectforjob.businessapi.directory;

import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import io.projectforjob.businessapi.api.InsertId;
import io.projectforjob.businessapi.directory.DirectoryData.ProjectMemberRow;
import io.projectforjob.businessapi.directory.DirectoryData.ProjectRow;
import io.projectforjob.businessapi.directory.DirectoryData.SpaceMemberRow;
import io.projectforjob.businessapi.directory.DirectoryData.WorkspaceRow;

@Mapper
interface DirectoryMapper {

    String SPACE_SCOPE = """
            FROM workspace w
            JOIN workspace_member wm ON wm.workspace_id = w.id
            JOIN app_user u ON u.id = wm.user_id
            WHERE u.id = #{userId} AND u.status = 'ACTIVE' AND wm.status = 'ACTIVE'
            """;
    String PROJECT_SCOPE = """
            FROM project p
            JOIN project_member pm ON pm.project_id = p.id
            JOIN workspace_member wm ON wm.id = pm.workspace_member_id
            JOIN app_user u ON u.id = wm.user_id
            WHERE u.id = #{userId} AND u.status = 'ACTIVE'
              AND wm.status = 'ACTIVE' AND pm.status = 'ACTIVE'
            """;
    String PROJECT_COLUMNS = """
            SELECT p.id, p.workspace_id, pm.id AS member_id, p.name, p.goal, p.status, pm.role,
                   p.version, p.created_at, p.updated_at
            """;
    String SPACE_MEMBER_COLUMNS = """
            SELECT wm.id, u.id AS user_id, u.login_name, u.display_name, u.status AS account_status,
                   wm.role, wm.status, wm.version
            """;
    String SPACE_MEMBERS = """
            FROM workspace_member wm JOIN app_user u ON u.id = wm.user_id
            WHERE wm.workspace_id = #{workspaceId}
            """;
    String PROJECT_MEMBER_COLUMNS = """
            SELECT pm.id, wm.id AS workspace_member_id, u.id AS user_id, u.display_name, pm.role, pm.status,
                   (u.status = 'ACTIVE' AND wm.status = 'ACTIVE' AND pm.status = 'ACTIVE') AS effective, pm.version
            """;
    String PROJECT_MEMBERS = """
            FROM project_member pm JOIN workspace_member wm ON wm.id = pm.workspace_member_id
            JOIN app_user u ON u.id = wm.user_id
            WHERE pm.project_id = #{projectId}
            """;

    @Select("SELECT id FROM app_user WHERE login_name = #{loginName} AND status = 'ACTIVE'")
    Long activeUserId(String loginName);

    @Select("SELECT w.id, w.name, w.version, wm.role " + SPACE_SCOPE + " AND w.id = #{workspaceId}")
    WorkspaceRow workspace(long userId, long workspaceId);

    @Select("SELECT w.id, w.name, w.version, wm.role " + SPACE_SCOPE + " ORDER BY w.id DESC LIMIT #{limit} OFFSET #{offset}")
    List<WorkspaceRow> workspaces(long userId, int limit, long offset);

    @Select("SELECT COUNT(*) " + SPACE_SCOPE)
    long countWorkspaces(long userId);

    @Select("SELECT id FROM workspace WHERE id = #{workspaceId} FOR UPDATE")
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    Long lockWorkspace(long workspaceId);

    @Select("SELECT id FROM workspace WHERE id = #{workspaceId} FOR SHARE")
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    Long shareWorkspace(long workspaceId);

    @Select("SELECT id FROM project WHERE id = #{projectId} FOR UPDATE")
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    Long lockProject(long projectId);

    @Insert("INSERT INTO workspace (name, created_by_user_id) VALUES (#{name}, #{userId})")
    @Options(useGeneratedKeys = true, keyProperty = "key.id")
    void createWorkspace(String name, long userId, InsertId key);

    @Select(SPACE_MEMBER_COLUMNS + SPACE_MEMBERS + " AND wm.user_id = #{userId}")
    SpaceMemberRow workspaceMemberByUser(long workspaceId, long userId);

    @Select(SPACE_MEMBER_COLUMNS + SPACE_MEMBERS + " AND wm.id = #{memberId}")
    SpaceMemberRow workspaceMember(long workspaceId, long memberId);

    @Select(SPACE_MEMBER_COLUMNS + SPACE_MEMBERS + " ORDER BY wm.id DESC LIMIT #{limit} OFFSET #{offset}")
    List<SpaceMemberRow> workspaceMembers(long workspaceId, int limit, long offset);

    @Select("SELECT COUNT(*) " + SPACE_MEMBERS)
    long countWorkspaceMembers(long workspaceId);

    @Insert("""
            INSERT INTO workspace_member (workspace_id, user_id, role, status)
            VALUES (#{workspaceId}, #{userId}, #{role}, 'ACTIVE')
            """)
    void addWorkspaceMember(long workspaceId, long userId, String role);

    @Update("""
            UPDATE workspace_member SET role = #{role}, status = 'ACTIVE',
                version = version + 1, updated_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{id}
            """)
    void restoreWorkspaceMember(long id, String role);

    @Select(PROJECT_COLUMNS + PROJECT_SCOPE + " AND p.id = #{projectId}")
    ProjectRow project(long userId, long projectId);

    @Select(PROJECT_COLUMNS + PROJECT_SCOPE + " AND p.workspace_id = #{workspaceId} ORDER BY p.id DESC LIMIT #{limit} OFFSET #{offset}")
    List<ProjectRow> projects(long userId, long workspaceId, int limit, long offset);

    @Select("SELECT COUNT(*) " + PROJECT_SCOPE + " AND p.workspace_id = #{workspaceId}")
    long countProjects(long userId, long workspaceId);

    @Insert("""
            INSERT INTO project (workspace_id, name, goal, status, created_by_user_id)
            VALUES (#{workspaceId}, #{name}, #{goal}, 'ACTIVE', #{userId})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "key.id")
    void createProject(long workspaceId, String name, String goal, long userId, InsertId key);

    @Select(PROJECT_MEMBER_COLUMNS + PROJECT_MEMBERS + " AND pm.workspace_member_id = #{memberId}")
    ProjectMemberRow projectMemberByWorkspaceMember(long projectId, long memberId);

    @Select(PROJECT_MEMBER_COLUMNS + PROJECT_MEMBERS + " AND pm.id = #{memberId}")
    ProjectMemberRow projectMember(long projectId, long memberId);

    @Select(PROJECT_MEMBER_COLUMNS + PROJECT_MEMBERS + " ORDER BY pm.id DESC LIMIT #{limit} OFFSET #{offset}")
    List<ProjectMemberRow> projectMembers(long projectId, int limit, long offset);

    @Select("SELECT COUNT(*) " + PROJECT_MEMBERS)
    long countProjectMembers(long projectId);

    @Insert("""
            INSERT INTO project_member (workspace_id, project_id, workspace_member_id, role, status)
            VALUES (#{workspaceId}, #{projectId}, #{workspaceMemberId}, #{role}, 'ACTIVE')
            """)
    void addProjectMember(long workspaceId, long projectId, long workspaceMemberId, String role);

    @Update("""
            UPDATE project_member SET role = #{role}, status = 'ACTIVE',
                version = version + 1, updated_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{id}
            """)
    void restoreProjectMember(long id, String role);
}
