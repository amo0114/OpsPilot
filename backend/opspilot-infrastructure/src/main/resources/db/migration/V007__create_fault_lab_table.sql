-- 故障实验室：fault_experiment（04 §62～§64、05 §68～§72、09 §13～§20，08 TASK-090）。
-- 沿用 V001～V006 约定：UTC DATETIME(3)；外键 RESTRICT；枚举按字节比较；审计事实不物理删除，不使用 Trigger。
-- 场景定义由代码维护，库内只记录每一次真实演练；不进入核心 Incident 领域。目标资源属于该系统、同一系统同时最多一个进行中的实验
-- 由 Java 在锁定 managed_system 父行的事务中检查（09 §13：实验之间先 RESET）。
-- ground_truth_payload 是实验答案（04 §64）：只供 Fault Lab 与 Evaluation 代码路径读取，不进入调查上下文或普通产品 API。

CREATE TABLE fault_experiment (
    id                           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    scenario_key                 VARCHAR(64)     NOT NULL,
    managed_system_id            BIGINT UNSIGNED NOT NULL,
    target_resource_id           BIGINT UNSIGNED NOT NULL,
    -- 注入确认生效后与 ACTIVE 同事务创建的 Incident；注入失败不创建（05 §71）
    incident_id                  BIGINT UNSIGNED NULL,
    status                       VARCHAR(16)     NOT NULL,
    -- 故障真正开始生效的时间（09 §19，即 Incident.started_at）
    injected_at                  DATETIME(3)     NULL,
    reset_at                     DATETIME(3)     NULL,
    ground_truth_schema_name     VARCHAR(128)    NOT NULL,
    ground_truth_schema_version  INT UNSIGNED    NOT NULL,
    ground_truth_payload         JSON            NOT NULL,
    -- 必须是脱敏后的文本（07 §99），不含凭据或控制命令
    error_message                VARCHAR(1000)   NULL,
    created_at                   DATETIME(3)     NOT NULL,
    updated_at                   DATETIME(3)     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_fault_experiment_incident UNIQUE (incident_id),
    INDEX idx_fault_experiment_system_status (managed_system_id, status),
    INDEX idx_fault_experiment_status (status, id),
    CONSTRAINT fk_fault_experiment_system FOREIGN KEY (managed_system_id)
        REFERENCES managed_system (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_fault_experiment_target FOREIGN KEY (target_resource_id)
        REFERENCES managed_resource (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_fault_experiment_incident FOREIGN KEY (incident_id)
        REFERENCES incident (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_fault_experiment_scenario_key CHECK (
        REGEXP_LIKE(scenario_key, '^[a-z][a-z0-9]*(-[a-z0-9]+)*\\z', 'c')),
    CONSTRAINT ck_fault_experiment_status CHECK (
        CAST(status AS BINARY) IN ('INJECTING', 'ACTIVE', 'RESETTING', 'RESET', 'FAILED')),
    -- 载荷内自带 schemaName / schemaVersion 且与伴随列一致（04 §98）；缺失路径为 NULL，用 IS TRUE 判为违反（同 V002）
    CONSTRAINT ck_fault_experiment_ground_truth CHECK ((
        CHAR_LENGTH(TRIM(ground_truth_schema_name)) > 0
        AND ground_truth_schema_version >= 1
        AND JSON_TYPE(ground_truth_payload) = 'OBJECT'
        AND JSON_TYPE(JSON_EXTRACT(ground_truth_payload, '$.schemaName')) = 'STRING'
        AND CAST(JSON_UNQUOTE(JSON_EXTRACT(ground_truth_payload, '$.schemaName')) AS BINARY)
            = CAST(ground_truth_schema_name AS BINARY)
        AND JSON_TYPE(JSON_EXTRACT(ground_truth_payload, '$.schemaVersion')) IN ('INTEGER', 'UNSIGNED INTEGER')
        AND JSON_EXTRACT(ground_truth_payload, '$.schemaVersion') = ground_truth_schema_version) IS TRUE),
    CONSTRAINT ck_fault_experiment_error_message CHECK (
        error_message IS NULL OR CHAR_LENGTH(TRIM(error_message)) > 0),
    -- INJECTING：尚未确认生效，没有 Incident；ACTIVE：已确认生效并有 Incident；RESET 必有重置时间；FAILED 必有错误信息。
    -- RESETTING/RESET 可来自 ACTIVE（有 Incident）或 FAILED（可能没有），保留之前的错误信息作为审计
    CONSTRAINT ck_fault_experiment_state CHECK (
        (CAST(status AS BINARY) = 'INJECTING' AND incident_id IS NULL AND injected_at IS NULL AND reset_at IS NULL
            AND error_message IS NULL)
        OR (CAST(status AS BINARY) = 'ACTIVE' AND incident_id IS NOT NULL AND injected_at IS NOT NULL
            AND reset_at IS NULL AND error_message IS NULL)
        OR (CAST(status AS BINARY) = 'RESETTING' AND reset_at IS NULL)
        OR (CAST(status AS BINARY) = 'RESET' AND reset_at IS NOT NULL)
        OR (CAST(status AS BINARY) = 'FAILED' AND reset_at IS NULL AND error_message IS NOT NULL)),
    CONSTRAINT ck_fault_experiment_times CHECK (
        injected_at IS NULL OR reset_at IS NULL OR injected_at <= reset_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
