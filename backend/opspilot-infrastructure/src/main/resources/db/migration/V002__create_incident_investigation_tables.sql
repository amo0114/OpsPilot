-- 故障核心：incident、incident_affected_resource、investigation、incident_timeline_event（04 §12～§16、§55～§58、§98）。
-- 沿用 V001 约定：时间由应用层按 UTC 写入 DATETIME(3)；外键 RESTRICT；枚举 CHECK 用 CAST(... AS BINARY) 逐字节比较；
-- 正则用 \z 锚定整串并区分大小写。故障审计数据禁止物理删除（04 §4），也不使用 Trigger（04 §86）。
-- 跨表语义（受影响资源属于 Incident 的系统、计数等于已准入 Invocation 数等）由 Java 在同一事务检查（04 §85、§98）。

CREATE TABLE incident (
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    incident_key       VARCHAR(32)     NOT NULL,
    managed_system_id  BIGINT UNSIGNED NOT NULL,
    title              VARCHAR(200)    NOT NULL,
    description        VARCHAR(2000)   NULL,
    impact_summary     VARCHAR(1000)   NOT NULL,
    status             VARCHAR(32)     NOT NULL,
    created_source     VARCHAR(32)     NOT NULL,
    created_by         VARCHAR(128)    NOT NULL,
    started_at         DATETIME(3)     NOT NULL,
    detected_at        DATETIME(3)     NOT NULL,
    resolved_at        DATETIME(3)     NULL,
    created_at         DATETIME(3)     NOT NULL,
    updated_at         DATETIME(3)     NOT NULL,
    -- 所有状态迁移按 status + lock_version 条件更新（04 §14、DB-INV-002）
    lock_version       BIGINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_incident_key UNIQUE (incident_key),
    INDEX idx_incident_system_status_created (managed_system_id, status, created_at),
    INDEX idx_incident_status_created (status, created_at),
    INDEX idx_incident_detected (detected_at),
    CONSTRAINT fk_incident_system FOREIGN KEY (managed_system_id)
        REFERENCES managed_system (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_incident_key CHECK (REGEXP_LIKE(incident_key, '^INC-[0-9]{8}-[0-9]{4,}\\z', 'c')),
    CONSTRAINT ck_incident_title CHECK (CHAR_LENGTH(TRIM(title)) > 0),
    CONSTRAINT ck_incident_impact_summary CHECK (CHAR_LENGTH(TRIM(impact_summary)) > 0),
    -- 冻结的 8 个状态（01、04 §13），不得增删
    CONSTRAINT ck_incident_status CHECK (CAST(status AS BINARY) IN
        ('CREATED', 'INVESTIGATING', 'DIAGNOSED', 'AWAITING_APPROVAL', 'EXECUTING', 'VERIFYING', 'RESOLVED',
         'CANCELLED')),
    -- 人工创建（05 §20）或 Fault Lab 注入确认后创建（05 §70）
    CONSTRAINT ck_incident_created_source CHECK (CAST(created_source AS BINARY) IN ('MANUAL', 'FAULT_LAB')),
    CONSTRAINT ck_incident_created_by CHECK (CHAR_LENGTH(TRIM(created_by)) > 0),
    -- RESOLVED 是终态，当且仅当已解决才有解决时间
    CONSTRAINT ck_incident_resolved_at CHECK ((CAST(status AS BINARY) = 'RESOLVED') = (resolved_at IS NOT NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Incident N:M ManagedResource，不以 JSON 保存资源 ID（04 §15）
CREATE TABLE incident_affected_resource (
    incident_id          BIGINT UNSIGNED NOT NULL,
    managed_resource_id  BIGINT UNSIGNED NOT NULL,
    created_at           DATETIME(3)     NOT NULL,
    PRIMARY KEY (incident_id, managed_resource_id),
    INDEX idx_incident_affected_resource_resource (managed_resource_id),
    CONSTRAINT fk_incident_affected_resource_incident FOREIGN KEY (incident_id)
        REFERENCES incident (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_incident_affected_resource_resource FOREIGN KEY (managed_resource_id)
        REFERENCES managed_resource (id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 一事故一调查工作空间；运行周期是逻辑序号，不建 InvestigationRun 表（04 §16）。
-- 限制值在首次创建时从配置快照，无数据库默认值；计数的业务一致性由准入事务保证。
CREATE TABLE investigation (
    id                            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    incident_id                   BIGINT UNSIGNED NOT NULL,
    started_at                    DATETIME(3)     NOT NULL,
    last_activity_at              DATETIME(3)     NOT NULL,
    current_run_no                INT UNSIGNED    NOT NULL,
    current_run_started_at        DATETIME(3)     NOT NULL,
    current_run_capability_count  INT UNSIGNED    NOT NULL DEFAULT 0,
    capability_call_count         BIGINT UNSIGNED NOT NULL DEFAULT 0,
    consecutive_ai_failure_count  INT UNSIGNED    NOT NULL DEFAULT 0,
    stop_requested_at             DATETIME(3)     NULL,
    stop_requested_by             VARCHAR(128)    NULL,
    max_capability_calls          INT UNSIGNED    NOT NULL,
    max_duration_seconds          INT UNSIGNED    NOT NULL,
    agent_step_timeout_seconds    INT UNSIGNED    NOT NULL,
    max_consecutive_ai_failures   INT UNSIGNED    NOT NULL,
    created_at                    DATETIME(3)     NOT NULL,
    updated_at                    DATETIME(3)     NOT NULL,
    lock_version                  BIGINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_investigation_incident UNIQUE (incident_id),
    CONSTRAINT fk_investigation_incident FOREIGN KEY (incident_id)
        REFERENCES incident (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    -- 04 §98 必须验证的运行控制约束
    CONSTRAINT ck_investigation_run_no CHECK (current_run_no >= 1),
    CONSTRAINT ck_investigation_run_capability_count CHECK (current_run_capability_count <= max_capability_calls),
    CONSTRAINT ck_investigation_stop_request CHECK ((stop_requested_at IS NULL) = (stop_requested_by IS NULL)),
    CONSTRAINT ck_investigation_stop_requested_by CHECK (
        stop_requested_by IS NULL OR CHAR_LENGTH(TRIM(stop_requested_by)) > 0),
    CONSTRAINT ck_investigation_limits CHECK (
        max_capability_calls >= 1 AND max_duration_seconds >= 1 AND agent_step_timeout_seconds >= 1
        AND max_consecutive_ai_failures >= 1)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 只追加的事故历史账本（04 §55～§58），无 updated_at / lock_version。
-- 同 Incident 的追加先取 Incident 行锁，按 (incident_id, id) 游标补读（04 §57）。
CREATE TABLE incident_timeline_event (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    incident_id     BIGINT UNSIGNED NOT NULL,
    event_type      VARCHAR(64)     NOT NULL,
    occurred_at     DATETIME(3)     NOT NULL,
    actor_type      VARCHAR(16)     NOT NULL,
    actor_id        VARCHAR(128)    NULL,
    summary         VARCHAR(1000)   NOT NULL,
    payload         JSON            NOT NULL,
    correlation_id  VARCHAR(64)     NULL,
    created_at      DATETIME(3)     NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_incident_timeline_event_incident (incident_id, id),
    CONSTRAINT fk_incident_timeline_event_incident FOREIGN KEY (incident_id)
        REFERENCES incident (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    -- 事件类型随各 Task 增加（01 §35 为最小集合），库内只约束格式
    CONSTRAINT ck_incident_timeline_event_type CHECK (REGEXP_LIKE(event_type, '^[A-Z][A-Z0-9_]*\\z', 'c')),
    CONSTRAINT ck_incident_timeline_event_actor_type CHECK (
        CAST(actor_type AS BINARY) IN ('USER', 'SYSTEM', 'AI_RUNTIME')),
    CONSTRAINT ck_incident_timeline_event_actor_id CHECK (actor_id IS NULL OR CHAR_LENGTH(TRIM(actor_id)) > 0),
    CONSTRAINT ck_incident_timeline_event_summary CHECK (CHAR_LENGTH(TRIM(summary)) > 0),
    -- 表无伴随 schema 列，载荷内自带 schemaName / schemaVersion（04 §98）。
    -- 缺失路径时 JSON_EXTRACT 为 NULL，而 CHECK 结果为 NULL 视为通过，因此整体用 IS TRUE 把 NULL 判为违反。
    CONSTRAINT ck_incident_timeline_event_payload CHECK ((
        JSON_TYPE(payload) = 'OBJECT'
        AND JSON_TYPE(JSON_EXTRACT(payload, '$.schemaName')) = 'STRING'
        AND CHAR_LENGTH(TRIM(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.schemaName')))) > 0
        AND JSON_TYPE(JSON_EXTRACT(payload, '$.schemaVersion')) IN ('INTEGER', 'UNSIGNED INTEGER')
        AND JSON_EXTRACT(payload, '$.schemaVersion') >= 1) IS TRUE)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
