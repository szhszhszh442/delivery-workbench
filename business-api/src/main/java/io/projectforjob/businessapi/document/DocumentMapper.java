package io.projectforjob.businessapi.document;

import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import io.projectforjob.businessapi.api.InsertId;
import io.projectforjob.businessapi.document.DocumentData.Content;
import io.projectforjob.businessapi.document.DocumentData.DocumentRow;
import io.projectforjob.businessapi.document.DocumentData.RevisionInfoRow;
import io.projectforjob.businessapi.document.DocumentData.RevisionRow;

@Mapper
interface DocumentMapper {

    String DOCUMENT_COLUMNS = "id, project_id, kind, title, current_revision_id, revision_seq, version, updated_at";
    String FILTER = """
            FROM document WHERE project_id = #{projectId}
            <if test="kind != null">AND kind = #{kind}</if>
            <if test="!query.isEmpty()">AND LOCATE(#{query}, title) > 0</if>
            """;

    @Select("<script> SELECT " + DOCUMENT_COLUMNS + " " + FILTER
            + " ORDER BY id DESC LIMIT #{limit} OFFSET #{offset} </script>")
    List<DocumentRow> list(long projectId, String kind, String query, int limit, long offset);

    @Select("<script> SELECT COUNT(*) " + FILTER + " </script>")
    long count(long projectId, String kind, String query);

    @Select("SELECT " + DOCUMENT_COLUMNS + " FROM document WHERE project_id = #{projectId} AND id = #{documentId}")
    DocumentRow find(long projectId, long documentId);

    @Select("""
            SELECT id, document_id, revision_no, title, editor_member_id, change_note, created_at
            FROM document_revision WHERE project_id = #{projectId} AND document_id = #{documentId}
            ORDER BY revision_no DESC LIMIT #{limit} OFFSET #{offset}
            """)
    List<RevisionInfoRow> history(long projectId, long documentId, int limit, long offset);

    @Select("SELECT COUNT(*) FROM document_revision WHERE project_id = #{projectId} AND document_id = #{documentId}")
    long countRevisions(long projectId, long documentId);

    @Select("""
            SELECT id, document_id, revision_no, title, content_markdown, editor_member_id, change_note, created_at
            FROM document_revision WHERE project_id = #{projectId} AND document_id = #{documentId} AND id = #{revisionId}
            """)
    RevisionRow revision(long projectId, long documentId, long revisionId);

    @Select("SELECT EXISTS(SELECT 1 FROM document_revision WHERE project_id = #{projectId} AND id = #{revisionId})")
    boolean hasRevision(long projectId, long revisionId);

    @Insert("""
            INSERT INTO document (project_id, kind, title, created_by_member_id)
            VALUES (#{projectId}, #{kind}, #{title}, #{memberId})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "key.id")
    void create(long projectId, String kind, String title, long memberId, InsertId key);

    @Insert("""
            INSERT INTO document_revision (project_id, document_id, revision_no, title, content_markdown,
                content_sha256, editor_member_id, change_note)
            VALUES (#{projectId}, #{documentId}, #{sequence}, #{content.title}, #{content.contentMarkdown},
                #{hash}, #{memberId}, #{content.changeNote})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "key.id")
    void append(long projectId, long documentId, int sequence, Content content, String hash, long memberId, InsertId key);

    @Update("""
            UPDATE document SET current_revision_id = #{revisionId}, revision_seq = #{sequence}, title = #{title},
                version = #{nextVersion}, updated_at = CURRENT_TIMESTAMP(3)
            WHERE project_id = #{projectId} AND id = #{documentId} AND version = #{baseVersion}
                AND current_revision_id <=> #{baseRevisionId,jdbcType=BIGINT}
            """)
    int advance(long projectId, long documentId, Long baseRevisionId, int baseVersion, long revisionId,
            int sequence, String title, int nextVersion);

    @Insert("""
            INSERT INTO activity_event (project_id, actor_user_id, actor_member_id, event_type, entity_type,
                entity_id, command_id, payload)
            VALUES (#{projectId}, #{actor}, #{memberId}, #{event}, 'DOCUMENT', #{documentId}, #{commandId}, #{payload})
            """)
    void recordActivity(long projectId, long actor, long memberId, String event, long documentId, long commandId,
            String payload);
}
