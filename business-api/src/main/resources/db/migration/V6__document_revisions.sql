CREATE TABLE document (
    id BIGINT NOT NULL AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    kind VARCHAR(16) COLLATE utf8mb4_bin NOT NULL,
    title VARCHAR(200) NOT NULL,
    current_revision_id BIGINT NULL,
    revision_seq INT NOT NULL DEFAULT 0,
    created_by_member_id BIGINT NOT NULL,
    version INT NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_document_scope (project_id, id),
    KEY idx_document_kind (project_id, kind, id),
    CONSTRAINT fk_document_project FOREIGN KEY (project_id) REFERENCES project (id),
    CONSTRAINT fk_document_creator FOREIGN KEY (project_id, created_by_member_id) REFERENCES project_member (project_id, id),
    CONSTRAINT chk_document_kind CHECK (kind IN ('REQUIREMENT','PLAN','DECISION','DELIVERY','HANDOVER','GENERAL'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE document_revision (
    id BIGINT NOT NULL AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    document_id BIGINT NOT NULL,
    revision_no INT NOT NULL,
    title VARCHAR(200) NOT NULL,
    content_markdown LONGTEXT NOT NULL,
    content_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    editor_member_id BIGINT NOT NULL,
    change_note VARCHAR(500) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_document_revision_number (document_id, revision_no),
    UNIQUE KEY uq_document_revision_project (project_id, id),
    UNIQUE KEY uq_document_revision_parent (project_id, document_id, id),
    CONSTRAINT fk_revision_document FOREIGN KEY (project_id, document_id) REFERENCES document (project_id, id),
    CONSTRAINT fk_revision_editor FOREIGN KEY (project_id, editor_member_id) REFERENCES project_member (project_id, id),
    CONSTRAINT chk_revision_number CHECK (revision_no > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE document ADD CONSTRAINT fk_document_current_revision
    FOREIGN KEY (project_id, id, current_revision_id) REFERENCES document_revision (project_id, document_id, id);

ALTER TABLE submission_material
    DROP CHECK chk_material_payload,
    ADD COLUMN document_revision_id BIGINT NULL,
    ADD CONSTRAINT fk_material_revision FOREIGN KEY (project_id, document_revision_id)
        REFERENCES document_revision (project_id, id),
    ADD CONSTRAINT chk_material_payload CHECK (
        (kind = 'TEXT' AND body IS NOT NULL AND CHAR_LENGTH(TRIM(body)) > 0 AND url IS NULL AND document_revision_id IS NULL)
        OR (kind = 'LINK' AND body IS NULL AND url IS NOT NULL AND CHAR_LENGTH(TRIM(url)) > 0 AND document_revision_id IS NULL)
        OR (kind = 'DOCUMENT' AND body IS NULL AND url IS NULL AND document_revision_id IS NOT NULL)
    );
