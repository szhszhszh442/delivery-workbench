package io.projectforjob.businessapi.directory;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

import io.projectforjob.businessapi.api.InsertId;

@Mapper
interface CommandMapper {

    @Select("""
            SELECT workspace_id, project_id, action, request_hash, http_status, result
            FROM command_receipt WHERE user_id = #{userId} AND idempotency_key = #{key}
            """)
    Receipt find(long userId, String key);

    @Insert("""
            INSERT INTO command_receipt
                (user_id, idempotency_key, workspace_id, project_id, action, request_hash, http_status, result)
            VALUES
                (#{userId}, #{key}, #{workspaceId}, #{projectId}, #{action}, #{hash}, #{status}, #{result})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "inserted.id")
    void insert(long userId, String key, Long workspaceId, Long projectId, String action, String hash, int status,
            String result, InsertId inserted);

    record Receipt(Long workspaceId, Long projectId, String action, String requestHash, int httpStatus, String result) {
    }
}
