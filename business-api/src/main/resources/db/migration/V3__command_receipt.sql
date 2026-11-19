CREATE TABLE command_receipt (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    workspace_id BIGINT NULL,
    project_id BIGINT NULL,
    idempotency_key VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    action VARCHAR(200) COLLATE utf8mb4_bin NOT NULL,
    request_hash CHAR(64) COLLATE utf8mb4_bin NOT NULL,
    http_status INT NOT NULL,
    result JSON NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_command_receipt_user_key (user_id, idempotency_key),
    CONSTRAINT fk_command_receipt_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT fk_command_receipt_workspace FOREIGN KEY (workspace_id) REFERENCES workspace (id),
    CONSTRAINT fk_command_receipt_project FOREIGN KEY (workspace_id, project_id)
        REFERENCES project (workspace_id, id),
    CONSTRAINT chk_command_receipt_scope CHECK (project_id IS NULL OR workspace_id IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
