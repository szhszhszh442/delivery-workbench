CREATE TABLE task (
    id BIGINT NOT NULL AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    parent_task_id BIGINT NULL,
    title VARCHAR(200) NOT NULL,
    goal LONGTEXT NOT NULL,
    expected_outcome LONGTEXT NOT NULL,
    acceptance_criteria JSON NOT NULL,
    open_questions JSON NOT NULL,
    execution_notes LONGTEXT NOT NULL,
    status VARCHAR(16) COLLATE utf8mb4_bin NOT NULL,
    creator_member_id BIGINT NOT NULL,
    assignee_member_id BIGINT NULL,
    reviewer_member_id BIGINT NULL,
    current_submission_id BIGINT NULL,
    submission_seq INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    version INT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uq_task_scope (project_id, id),
    KEY idx_task_status (project_id, status, id),
    KEY idx_task_assignee_status (project_id, assignee_member_id, status, id),
    KEY idx_task_reviewer_status (project_id, reviewer_member_id, status, id),
    KEY idx_task_parent (project_id, parent_task_id, id),
    CONSTRAINT fk_task_project FOREIGN KEY (project_id) REFERENCES project (id),
    CONSTRAINT fk_task_parent FOREIGN KEY (project_id, parent_task_id) REFERENCES task (project_id, id),
    CONSTRAINT fk_task_creator FOREIGN KEY (project_id, creator_member_id) REFERENCES project_member (project_id, id),
    CONSTRAINT fk_task_assignee FOREIGN KEY (project_id, assignee_member_id) REFERENCES project_member (project_id, id),
    CONSTRAINT fk_task_reviewer FOREIGN KEY (project_id, reviewer_member_id) REFERENCES project_member (project_id, id),
    CONSTRAINT chk_task_status CHECK (status IN ('DRAFT','READY','IN_PROGRESS','IN_REVIEW','DONE','CANCELLED')),
    CONSTRAINT chk_task_responsibilities CHECK (assignee_member_id IS NULL OR reviewer_member_id IS NULL
        OR assignee_member_id <> reviewer_member_id),
    CONSTRAINT chk_task_current_submission CHECK (
        (status = 'IN_REVIEW' AND current_submission_id IS NOT NULL)
        OR (status <> 'IN_REVIEW' AND current_submission_id IS NULL)
    ),
    CONSTRAINT chk_task_version CHECK (version > 0 AND submission_seq >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE activity_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    actor_user_id BIGINT NULL,
    actor_member_id BIGINT NULL,
    event_type VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    entity_type VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
    entity_id BIGINT NOT NULL,
    command_id BIGINT NULL,
    payload JSON NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_activity_project (project_id, id),
    CONSTRAINT fk_activity_project FOREIGN KEY (project_id) REFERENCES project (id),
    CONSTRAINT fk_activity_user FOREIGN KEY (actor_user_id) REFERENCES app_user (id),
    CONSTRAINT fk_activity_member FOREIGN KEY (project_id, actor_member_id) REFERENCES project_member (project_id, id),
    CONSTRAINT fk_activity_command FOREIGN KEY (command_id) REFERENCES command_receipt (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
