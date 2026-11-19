CREATE TABLE task_submission (
    id BIGINT NOT NULL AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    task_id BIGINT NOT NULL,
    submission_no INT NOT NULL,
    submitted_by_member_id BIGINT NOT NULL,
    assignee_snapshot_member_id BIGINT NOT NULL,
    reviewer_snapshot_member_id BIGINT NOT NULL,
    task_version_at_submit INT NOT NULL,
    scope_snapshot JSON NOT NULL,
    summary LONGTEXT NOT NULL,
    status VARCHAR(16) COLLATE utf8mb4_bin NOT NULL,
    ended_at DATETIME(3) NULL,
    ended_by_member_id BIGINT NULL,
    end_reason LONGTEXT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_submission_number (task_id, submission_no),
    UNIQUE KEY uq_submission_task (project_id, task_id, id),
    UNIQUE KEY uq_submission_scope (project_id, id),
    CONSTRAINT fk_submission_task FOREIGN KEY (project_id, task_id) REFERENCES task (project_id, id),
    CONSTRAINT fk_submission_submitter FOREIGN KEY (project_id, submitted_by_member_id) REFERENCES project_member (project_id, id),
    CONSTRAINT fk_submission_assignee FOREIGN KEY (project_id, assignee_snapshot_member_id) REFERENCES project_member (project_id, id),
    CONSTRAINT fk_submission_reviewer FOREIGN KEY (project_id, reviewer_snapshot_member_id) REFERENCES project_member (project_id, id),
    CONSTRAINT fk_submission_ender FOREIGN KEY (project_id, ended_by_member_id) REFERENCES project_member (project_id, id),
    CONSTRAINT chk_submission_status CHECK (status IN ('PENDING','APPROVED','RETURNED','WITHDRAWN','INVALIDATED')),
    CONSTRAINT chk_submission_numbers CHECK (submission_no > 0 AND task_version_at_submit > 0),
    CONSTRAINT chk_submission_snapshots CHECK (assignee_snapshot_member_id <> reviewer_snapshot_member_id
        AND submitted_by_member_id = assignee_snapshot_member_id),
    CONSTRAINT chk_submission_ended CHECK (
        (status = 'PENDING' AND ended_at IS NULL AND ended_by_member_id IS NULL AND end_reason IS NULL)
        OR (status IN ('APPROVED','RETURNED') AND ended_at IS NOT NULL AND ended_by_member_id IS NOT NULL AND end_reason IS NULL)
        OR (status IN ('WITHDRAWN','INVALIDATED') AND ended_at IS NOT NULL AND ended_by_member_id IS NOT NULL
            AND end_reason IS NOT NULL AND CHAR_LENGTH(TRIM(end_reason)) > 0)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE task ADD CONSTRAINT fk_task_current_submission
    FOREIGN KEY (project_id, id, current_submission_id) REFERENCES task_submission (project_id, task_id, id);

CREATE TABLE submission_material (
    id BIGINT NOT NULL AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    submission_id BIGINT NOT NULL,
    kind VARCHAR(16) COLLATE utf8mb4_bin NOT NULL,
    label VARCHAR(200) NOT NULL,
    body LONGTEXT NULL,
    url VARCHAR(2048) NULL,
    position INT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_material_scope (project_id, id),
    KEY idx_material_submission_position (submission_id, position, id),
    CONSTRAINT fk_material_submission FOREIGN KEY (project_id, submission_id) REFERENCES task_submission (project_id, id),
    CONSTRAINT chk_material_position CHECK (position >= 0),
    CONSTRAINT chk_material_payload CHECK (
        (kind = 'TEXT' AND body IS NOT NULL AND CHAR_LENGTH(TRIM(body)) > 0 AND url IS NULL)
        OR (kind = 'LINK' AND body IS NULL AND url IS NOT NULL AND CHAR_LENGTH(TRIM(url)) > 0)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE submission_decision (
    id BIGINT NOT NULL AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    submission_id BIGINT NOT NULL,
    reviewer_member_id BIGINT NOT NULL,
    decision VARCHAR(16) COLLATE utf8mb4_bin NOT NULL,
    comment LONGTEXT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_decision_scope (project_id, id),
    UNIQUE KEY uq_decision_submission (submission_id),
    CONSTRAINT fk_decision_submission FOREIGN KEY (project_id, submission_id) REFERENCES task_submission (project_id, id),
    CONSTRAINT fk_decision_member FOREIGN KEY (project_id, reviewer_member_id) REFERENCES project_member (project_id, id),
    CONSTRAINT chk_decision_value CHECK (decision IN ('APPROVE','RETURN')),
    CONSTRAINT chk_decision_comment CHECK (decision = 'APPROVE' OR CHAR_LENGTH(TRIM(comment)) > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE activity_event ADD UNIQUE KEY uq_activity_scope (project_id, id);

CREATE TABLE notification_delivery (
    id BIGINT NOT NULL AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    event_id BIGINT NOT NULL,
    recipient_member_ids JSON NOT NULL,
    status VARCHAR(16) COLLATE utf8mb4_bin NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    lease_until DATETIME(3) NULL,
    last_error VARCHAR(500) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_delivery_scope (project_id, id),
    UNIQUE KEY uq_delivery_event (event_id),
    KEY idx_delivery_due (status, next_attempt_at, id),
    CONSTRAINT fk_delivery_event FOREIGN KEY (project_id, event_id) REFERENCES activity_event (project_id, id),
    CONSTRAINT chk_delivery_status CHECK (status IN ('PENDING','RUNNING','DONE') AND attempts >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
