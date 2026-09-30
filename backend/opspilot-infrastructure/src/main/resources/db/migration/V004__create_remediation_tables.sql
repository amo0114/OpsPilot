-- 处理方案与审批：remediation_plan、remediation_action、approval_request（04 §38～§44、§76～§77、§84，08 TASK-062）。
-- 沿用 V001～V003 约定：UTC DATETIME(3)；外键 RESTRICT；枚举按字节比较；审计事实不物理删除，不使用 Trigger。
-- V0.1 一个 Plan 一个 Action、一个 Action 最多一条 Approval。Plan 与所属 Incident/当前 Diagnosis 的一致性、Approval 状态只向前
-- 由 Java 在同一事务按 Incident 行锁检查（04 §44、§98）；库内保护单表可表达的非空、取值与状态伴随字段。
-- 文本上限沿用 AI 协议 v1 取值（title 200、summary 2000、action.summary 500、expectedImpactSummary 1000）。

CREATE TABLE remediation_plan (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    incident_id   BIGINT UNSIGNED NOT NULL,
    diagnosis_id  BIGINT UNSIGNED NOT NULL,
    title         VARCHAR(200)    NOT NULL,
    summary       VARCHAR(2000)   NOT NULL,
    status        VARCHAR(16)     NOT NULL,
    created_at    DATETIME(3)     NOT NULL,
    updated_at    DATETIME(3)     NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_remediation_plan_incident_status (incident_id, status),
    INDEX idx_remediation_plan_diagnosis (diagnosis_id),
    CONSTRAINT fk_remediation_plan_incident FOREIGN KEY (incident_id)
        REFERENCES incident (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_remediation_plan_diagnosis FOREIGN KEY (diagnosis_id)
        REFERENCES diagnosis (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    -- EXECUTED 只表示已发生执行尝试，不表示成功或 RESOLVED（04 §38）
    CONSTRAINT ck_remediation_plan_status CHECK (
        CAST(status AS BINARY) IN ('ACTIVE', 'SUPERSEDED', 'CANCELLED', 'EXECUTED')),
    CONSTRAINT ck_remediation_plan_title CHECK (CHAR_LENGTH(TRIM(title)) > 0),
    CONSTRAINT ck_remediation_plan_summary CHECK (CHAR_LENGTH(TRIM(summary)) > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 只保存能力键、目标资源与强类型参数，不保存命令或容器身份（04 §42）；风险与是否审批由 Java Registry 决定并持久化（05 §88、06 §13）
CREATE TABLE remediation_action (
    id                        BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    remediation_plan_id       BIGINT UNSIGNED NOT NULL,
    capability_key            VARCHAR(64)     NOT NULL,
    target_resource_id        BIGINT UNSIGNED NOT NULL,
    parameter_schema_name     VARCHAR(128)    NOT NULL,
    parameter_schema_version  INT UNSIGNED    NOT NULL,
    parameter_payload         JSON            NOT NULL,
    summary                   VARCHAR(500)    NOT NULL,
    expected_impact_summary   VARCHAR(1000)   NOT NULL,
    risk_level                VARCHAR(16)     NOT NULL,
    requires_approval         BOOLEAN         NOT NULL,
    created_at                DATETIME(3)     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_remediation_action_plan UNIQUE (remediation_plan_id),
    INDEX idx_remediation_action_target (target_resource_id),
    CONSTRAINT fk_remediation_action_plan FOREIGN KEY (remediation_plan_id)
        REFERENCES remediation_plan (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_remediation_action_target FOREIGN KEY (target_resource_id)
        REFERENCES managed_resource (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_remediation_action_capability_key CHECK (
        REGEXP_LIKE(capability_key, '^[a-z]+[.][a-z]+\\z', 'c')),
    CONSTRAINT ck_remediation_action_parameters CHECK (
        CHAR_LENGTH(TRIM(parameter_schema_name)) > 0 AND parameter_schema_version >= 1
        AND JSON_TYPE(parameter_payload) = 'OBJECT'),
    CONSTRAINT ck_remediation_action_summary CHECK (CHAR_LENGTH(TRIM(summary)) > 0),
    CONSTRAINT ck_remediation_action_expected_impact CHECK (CHAR_LENGTH(TRIM(expected_impact_summary)) > 0),
    CONSTRAINT ck_remediation_action_risk_level CHECK (CAST(risk_level AS BINARY) IN ('LOW', 'MEDIUM')),
    -- V0.1 方案动作都是 CHANGE，CHANGE 必须人工审批（06 §12、CAP-INV-014）
    CONSTRAINT ck_remediation_action_requires_approval CHECK (requires_approval = TRUE)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 决定只向前（PENDING → APPROVED / REJECTED / CANCELLED），不可反转（04 §44）：由 Java 以 status + lock_version 条件更新保证
CREATE TABLE approval_request (
    id                     BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    remediation_action_id  BIGINT UNSIGNED NOT NULL,
    status                 VARCHAR(16)     NOT NULL,
    requested_at           DATETIME(3)     NOT NULL,
    decided_by             VARCHAR(128)    NULL,
    decided_at             DATETIME(3)     NULL,
    comment                VARCHAR(500)    NULL,
    created_at             DATETIME(3)     NOT NULL,
    updated_at             DATETIME(3)     NOT NULL,
    lock_version           BIGINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_approval_request_action UNIQUE (remediation_action_id),
    CONSTRAINT fk_approval_request_action FOREIGN KEY (remediation_action_id)
        REFERENCES remediation_action (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_approval_request_status CHECK (
        CAST(status AS BINARY) IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
    -- PENDING 尚无决定；其余状态必须记录决定人与决定时间（01 §25 自审批也要留痕），comment 只能随决定出现
    CONSTRAINT ck_approval_request_decision CHECK (
        (CAST(status AS BINARY) = 'PENDING' AND decided_by IS NULL AND decided_at IS NULL AND comment IS NULL)
        OR (CAST(status AS BINARY) <> 'PENDING' AND decided_by IS NOT NULL AND decided_at IS NOT NULL
            AND CHAR_LENGTH(TRIM(decided_by)) > 0)),
    CONSTRAINT ck_approval_request_comment CHECK (comment IS NULL OR CHAR_LENGTH(TRIM(comment)) > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
