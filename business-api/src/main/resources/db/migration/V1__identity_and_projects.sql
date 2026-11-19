CREATE TABLE app_user (
    id BIGINT NOT NULL AUTO_INCREMENT,
    login_name VARCHAR(64) NOT NULL,
    display_name VARCHAR(80) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    status VARCHAR(16) COLLATE utf8mb4_bin NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    version INT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uq_app_user_login_name (login_name),
    CONSTRAINT chk_app_user_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT chk_app_user_version CHECK (version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE workspace (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(120) NOT NULL,
    created_by_user_id BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    version INT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    CONSTRAINT fk_workspace_creator FOREIGN KEY (created_by_user_id) REFERENCES app_user (id),
    CONSTRAINT chk_workspace_version CHECK (version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE workspace_member (
    id BIGINT NOT NULL AUTO_INCREMENT,
    workspace_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role VARCHAR(16) COLLATE utf8mb4_bin NOT NULL,
    status VARCHAR(16) COLLATE utf8mb4_bin NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    version INT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uq_workspace_member_user (workspace_id, user_id),
    UNIQUE KEY uq_workspace_member_scope (workspace_id, id),
    KEY idx_workspace_member_user_status (user_id, status),
    CONSTRAINT fk_workspace_member_workspace FOREIGN KEY (workspace_id) REFERENCES workspace (id),
    CONSTRAINT fk_workspace_member_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT chk_workspace_member_role CHECK (role IN ('ADMIN', 'MEMBER')),
    CONSTRAINT chk_workspace_member_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT chk_workspace_member_version CHECK (version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE project (
    id BIGINT NOT NULL AUTO_INCREMENT,
    workspace_id BIGINT NOT NULL,
    name VARCHAR(120) NOT NULL,
    goal LONGTEXT NOT NULL,
    status VARCHAR(16) COLLATE utf8mb4_bin NOT NULL,
    created_by_user_id BIGINT NOT NULL,
    archived_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    version INT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uq_project_scope (workspace_id, id),
    KEY idx_project_workspace_status (workspace_id, status, id),
    CONSTRAINT fk_project_workspace FOREIGN KEY (workspace_id) REFERENCES workspace (id),
    CONSTRAINT fk_project_creator FOREIGN KEY (created_by_user_id) REFERENCES app_user (id),
    CONSTRAINT chk_project_archive CHECK (
        (status = 'ACTIVE' AND archived_at IS NULL)
        OR (status = 'ARCHIVED' AND archived_at IS NOT NULL)
    ),
    CONSTRAINT chk_project_version CHECK (version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE project_member (
    id BIGINT NOT NULL AUTO_INCREMENT,
    workspace_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    workspace_member_id BIGINT NOT NULL,
    role VARCHAR(16) COLLATE utf8mb4_bin NOT NULL,
    status VARCHAR(16) COLLATE utf8mb4_bin NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    version INT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uq_project_member_workspace_member (project_id, workspace_member_id),
    UNIQUE KEY uq_project_member_scope (project_id, id),
    KEY idx_project_member_workspace_member_status (workspace_member_id, status),
    CONSTRAINT fk_project_member_project FOREIGN KEY (workspace_id, project_id)
        REFERENCES project (workspace_id, id),
    CONSTRAINT fk_project_member_workspace_member FOREIGN KEY (workspace_id, workspace_member_id)
        REFERENCES workspace_member (workspace_id, id),
    CONSTRAINT chk_project_member_role CHECK (role IN ('OWNER', 'CONTRIBUTOR', 'OBSERVER')),
    CONSTRAINT chk_project_member_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT chk_project_member_version CHECK (version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
