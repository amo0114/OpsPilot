-- 写操作执行记录：action_execution（04 §45～§47、§79、§82、§98，08 TASK-068）。
-- 沿用 V001～V004 约定：UTC DATETIME(3)；外键 RESTRICT；枚举按字节比较；审计事实不物理删除，不使用 Trigger。
-- recovery_policy 表由 TASK-074（V006）创建：此处 recovery_policy_id 先建非空列，V006 必须补
-- fk_action_execution_policy（07 §92、08 TASK-068）。真实 PENDING 只在 TASK-069 批准事务中创建，此前不会写入本表。
-- 同一 Incident 最多一个进行中的 Execution、Approval 必须为 APPROVED、Policy 与 Action 目标资源一致等跨表条件
-- 由 Java 在批准事务中按行锁检查（01 §30、04 §78、§85）；库内保护执行身份唯一、状态伴随字段与核对计数上限。

-- 复合外键目标：Execution 引用的 Approval 必须属于同一 Action
ALTER TABLE approval_request
    ADD CONSTRAINT uk_approval_request_id_action UNIQUE (id, remediation_action_id);

CREATE TABLE action_execution (
    id                               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    remediation_action_id            BIGINT UNSIGNED NOT NULL,
    approval_request_id              BIGINT UNSIGNED NOT NULL,
    -- Java 由已冻结的 Action 身份确定性生成：固定为 'action-execution:<remediation_action_id>'（04 §47），
    -- 同一 Action 永远得到同一键，唯一冲突不能靠换键绕过
    idempotency_key                  VARCHAR(128)    NOT NULL,
    status                           VARCHAR(16)     NOT NULL,
    executor_key                     VARCHAR(64)     NOT NULL,
    -- 执行前冻结的恢复合同（04 §45、§52）；fk_action_execution_policy 由 V006 补齐
    recovery_policy_id               BIGINT UNSIGNED NOT NULL,
    recovery_policy_version          INT UNSIGNED    NOT NULL,
    recovery_policy_snapshot         JSON            NOT NULL,
    -- 受信解析的 Provider/目标容器身份，不含密码或任意命令（04 §45）
    execution_context_schema_name    VARCHAR(128)    NOT NULL,
    execution_context_schema_version INT UNSIGNED    NOT NULL,
    execution_context_payload        JSON            NOT NULL,
    result_schema_name               VARCHAR(128)    NULL,
    result_schema_version            INT UNSIGNED    NULL,
    result_payload                   JSON            NULL,
    error_code                       VARCHAR(64)     NULL,
    -- 必须是脱敏后的文本（06 §31～§32、07 §99）
    error_message                    VARCHAR(1000)   NULL,
    -- 有界只读核对（04 §82）：先登记次数与时间再发出 inspect；上限与截止时间不因重启刷新
    reconciliation_attempt_count     INT UNSIGNED    NOT NULL DEFAULT 0,
    max_reconciliation_attempts      INT UNSIGNED    NOT NULL,
    last_reconciliation_at           DATETIME(3)     NULL,
    reconciliation_deadline_at       DATETIME(3)     NULL,
    started_at                       DATETIME(3)     NULL,
    finished_at                      DATETIME(3)     NULL,
    correlation_id                   VARCHAR(64)     NULL,
    created_at                       DATETIME(3)     NOT NULL,
    updated_at                       DATETIME(3)     NOT NULL,
    lock_version                     BIGINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    -- 同一 Action 只有一个 Execution 身份（04 §46）
    CONSTRAINT uk_action_execution_action UNIQUE (remediation_action_id),
    CONSTRAINT uk_action_execution_idempotency_key UNIQUE (idempotency_key),
    -- 补派发与启动恢复按状态扫描（04 §21 同理）
    INDEX idx_action_execution_status (status, id),
    CONSTRAINT fk_action_execution_action FOREIGN KEY (remediation_action_id)
        REFERENCES remediation_action (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_action_execution_approval FOREIGN KEY (approval_request_id, remediation_action_id)
        REFERENCES approval_request (id, remediation_action_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_action_execution_idempotency_key CHECK (
        CAST(idempotency_key AS BINARY) = CAST(CONCAT('action-execution:', remediation_action_id) AS BINARY)),
    -- 不新增 UNKNOWN：结果无法确定时 FAILED + EXECUTION_RESULT_UNCERTAIN（04 §82、06 §111）
    CONSTRAINT ck_action_execution_status CHECK (
        CAST(status AS BINARY) IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_action_execution_executor_key CHECK (
        REGEXP_LIKE(executor_key, '^[a-z0-9][a-z0-9.-]*\\z', 'c')),
    -- 快照载荷内自带 schemaName / schemaVersion（04 §45、§98）；缺失路径为 NULL，用 IS TRUE 判为违反（同 V002）
    CONSTRAINT ck_action_execution_policy_snapshot CHECK (recovery_policy_version >= 1 AND (
        JSON_TYPE(recovery_policy_snapshot) = 'OBJECT'
        AND JSON_TYPE(JSON_EXTRACT(recovery_policy_snapshot, '$.schemaName')) = 'STRING'
        AND CHAR_LENGTH(TRIM(JSON_UNQUOTE(JSON_EXTRACT(recovery_policy_snapshot, '$.schemaName')))) > 0
        AND JSON_TYPE(JSON_EXTRACT(recovery_policy_snapshot, '$.schemaVersion')) IN ('INTEGER', 'UNSIGNED INTEGER')
        AND JSON_EXTRACT(recovery_policy_snapshot, '$.schemaVersion') >= 1) IS TRUE),
    CONSTRAINT ck_action_execution_context CHECK (
        CHAR_LENGTH(TRIM(execution_context_schema_name)) > 0 AND execution_context_schema_version >= 1
        AND JSON_TYPE(execution_context_payload) = 'OBJECT'),
    -- 结果三列同空同非空（同 V003 响应列）
    CONSTRAINT ck_action_execution_result CHECK ((
        (result_schema_name IS NULL AND result_schema_version IS NULL AND result_payload IS NULL)
        OR (CHAR_LENGTH(TRIM(result_schema_name)) > 0 AND result_schema_version >= 1
            AND JSON_TYPE(result_payload) = 'OBJECT')) IS TRUE),
    CONSTRAINT ck_action_execution_error_code CHECK (
        error_code IS NULL OR REGEXP_LIKE(error_code, '^[A-Z][A-Z0-9_]*\\z', 'c')),
    -- PENDING 尚未开始、未核对；RUNNING 已开始未结束；SUCCEEDED 有结果无错误码；FAILED 必有错误码。
    -- FAILED 可以没有 started_at：RUNNING 准入前的目标解析失败不发出 CHANGE（04 §45）
    CONSTRAINT ck_action_execution_outcome CHECK (
        (CAST(status AS BINARY) = 'PENDING' AND started_at IS NULL AND finished_at IS NULL
            AND result_payload IS NULL AND error_code IS NULL AND reconciliation_attempt_count = 0
            AND reconciliation_deadline_at IS NULL)
        OR (CAST(status AS BINARY) = 'RUNNING' AND started_at IS NOT NULL AND finished_at IS NULL
            AND result_payload IS NULL AND error_code IS NULL)
        OR (CAST(status AS BINARY) = 'SUCCEEDED' AND started_at IS NOT NULL AND finished_at IS NOT NULL
            AND result_payload IS NOT NULL AND error_code IS NULL)
        OR (CAST(status AS BINARY) = 'FAILED' AND finished_at IS NOT NULL AND error_code IS NOT NULL)),
    -- 核对次数不超过已快照上限（04 §98）；已登记尝试必有尝试时间与已冻结的截止时间
    CONSTRAINT ck_action_execution_reconciliation CHECK (
        reconciliation_attempt_count <= max_reconciliation_attempts
        AND ((reconciliation_attempt_count = 0 AND last_reconciliation_at IS NULL)
            OR (reconciliation_attempt_count > 0 AND last_reconciliation_at IS NOT NULL
                AND reconciliation_deadline_at IS NOT NULL)))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
