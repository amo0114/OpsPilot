-- 恢复合同与验证：recovery_policy、recovery_verification，并补齐恢复相关最终外键
-- （04 §18～§23、§48～§54、§80、§97～§98，07 §92，08 TASK-074）。
-- 沿用 V001～V005 约定：UTC DATETIME(3)；外键 RESTRICT；枚举按字节比较；审计事实不物理删除，不使用 Trigger。
-- 每个资源最多一个 ACTIVE 策略由激活事务锁 managed_resource 父行保证（04 §49，TASK-076）；同时最多一个 RUNNING
-- Verification、Verification 与其 Execution 属于同一 Incident 等跨表条件由 Java 同事务检查（01 §30、04 §85）。
-- 恢复样本唯一键 uk_capability_invocation_sample 已在 V003 建立，此处不重复。

-- 策略只能插入新版本、退休旧版本，不改写已用于快照的内容（01 §28、04 §48）
CREATE TABLE recovery_policy (
    id                       BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    managed_resource_id      BIGINT UNSIGNED NOT NULL,
    policy_key               VARCHAR(64)     NOT NULL,
    name                     VARCHAR(128)    NOT NULL,
    version_no               INT UNSIGNED    NOT NULL,
    criteria_schema_name     VARCHAR(128)    NOT NULL,
    criteria_schema_version  INT UNSIGNED    NOT NULL,
    criteria_payload         JSON            NOT NULL,
    status                   VARCHAR(16)     NOT NULL,
    created_at               DATETIME(3)     NOT NULL,
    activated_at             DATETIME(3)     NOT NULL,
    retired_at               DATETIME(3)     NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_recovery_policy_version UNIQUE (managed_resource_id, policy_key, version_no),
    -- 复合外键目标：引用方保存的版本号（Execution）及资源（Verification）必须是该策略行自身的
    CONSTRAINT uk_recovery_policy_id_version UNIQUE (id, version_no),
    CONSTRAINT uk_recovery_policy_identity UNIQUE (id, version_no, managed_resource_id),
    INDEX idx_recovery_policy_resource_status (managed_resource_id, status),
    CONSTRAINT fk_recovery_policy_resource FOREIGN KEY (managed_resource_id)
        REFERENCES managed_resource (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_recovery_policy_key CHECK (REGEXP_LIKE(policy_key, '^[a-z0-9][a-z0-9-]*\\z', 'c')),
    CONSTRAINT ck_recovery_policy_name CHECK (CHAR_LENGTH(TRIM(name)) > 0),
    CONSTRAINT ck_recovery_policy_version CHECK (version_no >= 1),
    -- Criteria 载荷内自带与伴随列一致的 schemaName / schemaVersion，且 Criteria 为非空有序数组（04 §80、06 §113）；
    -- 逐项字段、谓词与采样的合法性由 Codec 校验（TASK-075）。缺失路径为 NULL，用 IS TRUE 判为违反（同 V002）
    CONSTRAINT ck_recovery_policy_criteria CHECK ((
        CHAR_LENGTH(TRIM(criteria_schema_name)) > 0 AND criteria_schema_version >= 1
        AND JSON_TYPE(criteria_payload) = 'OBJECT'
        AND JSON_TYPE(JSON_EXTRACT(criteria_payload, '$.schemaName')) = 'STRING'
        AND CAST(JSON_UNQUOTE(JSON_EXTRACT(criteria_payload, '$.schemaName')) AS BINARY)
            = CAST(criteria_schema_name AS BINARY)
        AND JSON_TYPE(JSON_EXTRACT(criteria_payload, '$.schemaVersion')) IN ('INTEGER', 'UNSIGNED INTEGER')
        AND JSON_EXTRACT(criteria_payload, '$.schemaVersion') = criteria_schema_version
        AND JSON_TYPE(JSON_EXTRACT(criteria_payload, '$.criteria')) = 'ARRAY'
        AND JSON_LENGTH(criteria_payload, '$.criteria') > 0) IS TRUE),
    CONSTRAINT ck_recovery_policy_status CHECK (CAST(status AS BINARY) IN ('ACTIVE', 'RETIRED')),
    -- ACTIVE 没有退休时间；RETIRED 必须记录不早于激活的退休时间
    CONSTRAINT ck_recovery_policy_retirement CHECK (
        (CAST(status AS BINARY) = 'ACTIVE' AND retired_at IS NULL)
        OR (CAST(status AS BINARY) = 'RETIRED' AND retired_at IS NOT NULL AND retired_at >= activated_at))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 一次恢复判定：执行后或外部处理后的独立验证，结果不可覆盖，重验新建（01 §29、04 §50～§54）
CREATE TABLE recovery_verification (
    id                       BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    incident_id              BIGINT UNSIGNED NOT NULL,
    -- 外部人工处理后的 verify-recovery 没有 Execution（04 §54）
    action_execution_id      BIGINT UNSIGNED NULL,
    managed_resource_id      BIGINT UNSIGNED NOT NULL,
    recovery_policy_id       BIGINT UNSIGNED NOT NULL,
    recovery_policy_version  INT UNSIGNED    NOT NULL,
    -- 本次真正使用的恢复合同（01 §28、04 §52）
    policy_snapshot          JSON            NOT NULL,
    verification_no          INT UNSIGNED    NOT NULL,
    status                   VARCHAR(16)     NOT NULL,
    -- result_payload 的用户摘要，不是第二套判定（04 §50）
    result_summary           VARCHAR(1000)   NULL,
    -- 类型化 recovery.verification.result / 1，载荷内自带 schema 标识
    result_payload           JSON            NULL,
    -- 创建时按快照 maxDurationSeconds 冻结，重启不刷新（04 §80）
    deadline_at              DATETIME(3)     NOT NULL,
    started_at               DATETIME(3)     NULL,
    finished_at              DATETIME(3)     NULL,
    created_at               DATETIME(3)     NOT NULL,
    updated_at               DATETIME(3)     NOT NULL,
    lock_version             BIGINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    -- 同一 Execution 最多一个 Verification（DB-INV-003）；NULL 不受限制
    CONSTRAINT uk_recovery_verification_execution UNIQUE (action_execution_id),
    CONSTRAINT uk_recovery_verification_incident_no UNIQUE (incident_id, verification_no),
    -- Invocation / Observation 复合外键目标
    CONSTRAINT uk_recovery_verification_id_incident UNIQUE (id, incident_id),
    -- 补派发与启动恢复按状态扫描
    INDEX idx_recovery_verification_status (status, id),
    CONSTRAINT fk_recovery_verification_incident FOREIGN KEY (incident_id)
        REFERENCES incident (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_recovery_verification_execution FOREIGN KEY (action_execution_id)
        REFERENCES action_execution (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_recovery_verification_resource FOREIGN KEY (managed_resource_id)
        REFERENCES managed_resource (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    -- 策略版本与所验证资源必须与所引用的策略行一致（04 §51）
    CONSTRAINT fk_recovery_verification_policy FOREIGN KEY (recovery_policy_id, recovery_policy_version, managed_resource_id)
        REFERENCES recovery_policy (id, version_no, managed_resource_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_recovery_verification_no CHECK (verification_no >= 1),
    CONSTRAINT ck_recovery_verification_policy_snapshot CHECK ((
        JSON_TYPE(policy_snapshot) = 'OBJECT'
        AND JSON_TYPE(JSON_EXTRACT(policy_snapshot, '$.schemaName')) = 'STRING'
        AND CHAR_LENGTH(TRIM(JSON_UNQUOTE(JSON_EXTRACT(policy_snapshot, '$.schemaName')))) > 0
        AND JSON_TYPE(JSON_EXTRACT(policy_snapshot, '$.schemaVersion')) IN ('INTEGER', 'UNSIGNED INTEGER')
        AND JSON_EXTRACT(policy_snapshot, '$.schemaVersion') >= 1) IS TRUE),
    CONSTRAINT ck_recovery_verification_result_payload CHECK ((
        result_payload IS NULL
        OR (JSON_TYPE(result_payload) = 'OBJECT'
            AND JSON_TYPE(JSON_EXTRACT(result_payload, '$.schemaName')) = 'STRING'
            AND CHAR_LENGTH(TRIM(JSON_UNQUOTE(JSON_EXTRACT(result_payload, '$.schemaName')))) > 0
            AND JSON_TYPE(JSON_EXTRACT(result_payload, '$.schemaVersion')) IN ('INTEGER', 'UNSIGNED INTEGER')
            AND JSON_EXTRACT(result_payload, '$.schemaVersion') >= 1)) IS TRUE),
    CONSTRAINT ck_recovery_verification_result_summary CHECK (
        result_summary IS NULL OR CHAR_LENGTH(TRIM(result_summary)) > 0),
    CONSTRAINT ck_recovery_verification_status CHECK (
        CAST(status AS BINARY) IN ('PENDING', 'RUNNING', 'PASSED', 'FAILED', 'INCONCLUSIVE')),
    CONSTRAINT ck_recovery_verification_deadline CHECK (deadline_at >= created_at),
    -- PENDING 未开始无结果；RUNNING 已开始未结束，运行中结果可重建（可空）；终态必须有结束时间、结果与摘要。
    -- 终态可以没有 started_at：重启后已过原 deadline 的未开始验证按结果矩阵直接收束（04 §80）
    CONSTRAINT ck_recovery_verification_outcome CHECK (
        (CAST(status AS BINARY) = 'PENDING' AND started_at IS NULL AND finished_at IS NULL
            AND result_payload IS NULL AND result_summary IS NULL)
        OR (CAST(status AS BINARY) = 'RUNNING' AND started_at IS NOT NULL AND finished_at IS NULL
            AND result_summary IS NULL)
        OR (CAST(status AS BINARY) IN ('PASSED', 'FAILED', 'INCONCLUSIVE') AND finished_at IS NOT NULL
            AND result_payload IS NOT NULL AND result_summary IS NOT NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Execution 冻结的策略 id 与版本必须是同一策略行（04 §45、§52；TASK-068 先建列）
ALTER TABLE action_execution
    ADD CONSTRAINT fk_action_execution_policy FOREIGN KEY (recovery_policy_id, recovery_policy_version)
        REFERENCES recovery_policy (id, version_no) ON DELETE RESTRICT ON UPDATE RESTRICT;

-- 恢复样本与恢复观测的最终外键，并保证 incident_id 与所属 Verification 一致（04 §19～§20、§23，08 TASK-021）；
-- 调查上下文 recovery_verification_id 为 NULL，复合外键不检查
ALTER TABLE capability_invocation
    ADD CONSTRAINT fk_capability_invocation_verification FOREIGN KEY (recovery_verification_id, incident_id)
        REFERENCES recovery_verification (id, incident_id) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE observation
    ADD CONSTRAINT fk_observation_verification FOREIGN KEY (recovery_verification_id, incident_id)
        REFERENCES recovery_verification (id, incident_id) ON DELETE RESTRICT ON UPDATE RESTRICT;
