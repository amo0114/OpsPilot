-- 调查事实：capability_invocation、observation、hypothesis、evidence、diagnosis、diagnosis_evidence_ref、agent_step_record
-- （04 §17～§36、§59～§61，08 TASK-021）。沿用 V001/V002 约定：UTC DATETIME(3)；外键 RESTRICT；枚举按字节比较；
-- 正则 \z 整串且区分大小写；审计事实不物理删除，不使用 Trigger。
-- recovery_verification 表由 TASK-074 创建：此处 recovery_verification_id 先建可空列与索引，TASK-074 必须补
-- fk_capability_invocation_verification、fk_observation_verification（07 §92、08 TASK-021），终版不得遗留无约束引用。
-- 同一 Incident/Investigation 的跨表一致性能用复合外键表达的在库内保证，其余由 Java 同事务检查（04 §85、§98）。

-- 复合外键目标：子表同时引用 (investigation.id, investigation.incident_id)
ALTER TABLE investigation
    ADD CONSTRAINT uk_investigation_id_incident UNIQUE (id, incident_id);

-- 能力调用：调查调用或恢复采样调用之一（04 §18～§21）
CREATE TABLE capability_invocation (
    id                        BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    incident_id               BIGINT UNSIGNED NOT NULL,
    investigation_id          BIGINT UNSIGNED NULL,
    recovery_verification_id  BIGINT UNSIGNED NULL,
    run_no                    INT UNSIGNED    NULL,
    criterion_key             VARCHAR(128)    NULL,
    sample_index              INT UNSIGNED    NULL,
    capability_key            VARCHAR(64)     NOT NULL,
    managed_resource_id       BIGINT UNSIGNED NOT NULL,
    status                    VARCHAR(16)     NOT NULL,
    request_schema_name       VARCHAR(128)    NOT NULL,
    request_schema_version    INT UNSIGNED    NOT NULL,
    request_payload           JSON            NOT NULL,
    response_schema_name      VARCHAR(128)    NULL,
    response_schema_version   INT UNSIGNED    NULL,
    response_payload          JSON            NULL,
    raw_result_ref            VARCHAR(512)    NULL,
    started_at                DATETIME(3)     NOT NULL,
    finished_at               DATETIME(3)     NULL,
    duration_ms               BIGINT UNSIGNED NULL,
    error_code                VARCHAR(64)     NULL,
    -- 必须是脱敏后的文本（06 §31～§32、07 §99）
    error_message             VARCHAR(1000)   NULL,
    correlation_id            VARCHAR(64)     NULL,
    created_at                DATETIME(3)     NOT NULL,
    updated_at                DATETIME(3)     NOT NULL,
    PRIMARY KEY (id),
    -- Observation 复合外键目标
    CONSTRAINT uk_capability_invocation_identity UNIQUE (id, incident_id, managed_resource_id),
    -- 恢复样本身份唯一（04 §18）；调查调用三列均为 NULL，不受限制
    CONSTRAINT uk_capability_invocation_sample UNIQUE (recovery_verification_id, criterion_key, sample_index),
    INDEX idx_capability_invocation_incident (incident_id, id),
    INDEX idx_capability_invocation_investigation (investigation_id, id),
    INDEX idx_capability_invocation_verification (recovery_verification_id, id),
    INDEX idx_capability_invocation_resource (managed_resource_id, created_at),
    CONSTRAINT fk_capability_invocation_incident FOREIGN KEY (incident_id)
        REFERENCES incident (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    -- 调查调用的 incident_id 必须与其 Investigation 所属 Incident 一致（04 §20）
    CONSTRAINT fk_capability_invocation_investigation FOREIGN KEY (investigation_id, incident_id)
        REFERENCES investigation (id, incident_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_capability_invocation_resource FOREIGN KEY (managed_resource_id)
        REFERENCES managed_resource (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    -- 04 §19 上下文互斥
    CONSTRAINT ck_capability_invocation_context CHECK (
        (investigation_id IS NOT NULL AND recovery_verification_id IS NULL
            AND run_no IS NOT NULL AND run_no >= 1 AND criterion_key IS NULL AND sample_index IS NULL)
        OR (investigation_id IS NULL AND recovery_verification_id IS NOT NULL
            AND run_no IS NULL AND criterion_key IS NOT NULL AND sample_index IS NOT NULL AND sample_index >= 1)),
    CONSTRAINT ck_capability_invocation_criterion_key CHECK (
        criterion_key IS NULL OR REGEXP_LIKE(criterion_key, '^[a-z0-9][a-z0-9-]*\\z', 'c')),
    CONSTRAINT ck_capability_invocation_key CHECK (REGEXP_LIKE(capability_key, '^[a-z]+[.][a-z]+\\z', 'c')),
    CONSTRAINT ck_capability_invocation_status CHECK (
        CAST(status AS BINARY) IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_capability_invocation_request CHECK (
        CHAR_LENGTH(TRIM(request_schema_name)) > 0 AND request_schema_version >= 1
        AND JSON_TYPE(request_payload) = 'OBJECT'),
    -- 响应三列同空同非空；成功必须有响应（04 §69 JSON 必带 Schema）。
    -- 部分为空时表达式为 NULL，而 CHECK 视 NULL 为通过，因此用 IS TRUE（同 V002）
    CONSTRAINT ck_capability_invocation_response CHECK ((
        (response_schema_name IS NULL AND response_schema_version IS NULL AND response_payload IS NULL)
        OR (CHAR_LENGTH(TRIM(response_schema_name)) > 0 AND response_schema_version >= 1
            AND JSON_TYPE(response_payload) = 'OBJECT')) IS TRUE),
    -- 终态才有结束时间与耗时；成功有响应，失败有错误码
    CONSTRAINT ck_capability_invocation_outcome CHECK (
        (CAST(status AS BINARY) IN ('PENDING', 'RUNNING') AND finished_at IS NULL AND duration_ms IS NULL
            AND response_payload IS NULL AND error_code IS NULL)
        OR (CAST(status AS BINARY) = 'SUCCEEDED' AND finished_at IS NOT NULL AND duration_ms IS NOT NULL
            AND response_payload IS NOT NULL AND error_code IS NULL)
        OR (CAST(status AS BINARY) = 'FAILED' AND finished_at IS NOT NULL AND duration_ms IS NOT NULL
            AND error_code IS NOT NULL)),
    CONSTRAINT ck_capability_invocation_error_code CHECK (
        error_code IS NULL OR REGEXP_LIKE(error_code, '^[A-Z][A-Z0-9_]*\\z', 'c')),
    -- 较大脱敏结果放受控本地目录，V0.1 为 file:// 引用（04 §92）
    CONSTRAINT ck_capability_invocation_raw_result_ref CHECK (
        raw_result_ref IS NULL OR REGEXP_LIKE(raw_result_ref, '^file://[^[:space:]]+\\z', 'c'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 不可变观测（04 §22～§26）：无 updated_at；Incident/资源与来源 Invocation 一致由复合外键保证，
-- 调查/恢复上下文一致由插入语句校验（TASK-022）
CREATE TABLE observation (
    id                        BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    incident_id               BIGINT UNSIGNED NOT NULL,
    investigation_id          BIGINT UNSIGNED NULL,
    recovery_verification_id  BIGINT UNSIGNED NULL,
    capability_invocation_id  BIGINT UNSIGNED NOT NULL,
    managed_resource_id       BIGINT UNSIGNED NOT NULL,
    observation_kind          VARCHAR(32)     NOT NULL,
    schema_name               VARCHAR(128)    NOT NULL,
    schema_version            INT UNSIGNED    NOT NULL,
    payload                   JSON            NOT NULL,
    summary                   VARCHAR(1000)   NOT NULL,
    observed_at               DATETIME(3)     NOT NULL,
    window_start              DATETIME(3)     NULL,
    window_end                DATETIME(3)     NULL,
    created_at                DATETIME(3)     NOT NULL,
    PRIMARY KEY (id),
    -- Evidence 复合外键目标：只能匹配调查 Observation
    CONSTRAINT uk_observation_id_investigation UNIQUE (id, investigation_id),
    INDEX idx_observation_incident (incident_id, id),
    INDEX idx_observation_investigation (investigation_id, id),
    INDEX idx_observation_verification (recovery_verification_id, id),
    CONSTRAINT fk_observation_incident FOREIGN KEY (incident_id)
        REFERENCES incident (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_observation_investigation FOREIGN KEY (investigation_id, incident_id)
        REFERENCES investigation (id, incident_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_observation_invocation FOREIGN KEY (capability_invocation_id, incident_id, managed_resource_id)
        REFERENCES capability_invocation (id, incident_id, managed_resource_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_observation_resource FOREIGN KEY (managed_resource_id)
        REFERENCES managed_resource (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_observation_context CHECK (
        (investigation_id IS NULL) <> (recovery_verification_id IS NULL)),
    -- 04 §25 粗分类，具体结构由 schema_name 决定
    CONSTRAINT ck_observation_kind CHECK (CAST(observation_kind AS BINARY) IN
        ('METRIC', 'LOG_PATTERN', 'SERVICE_STATUS', 'DATABASE_STATUS', 'CACHE_STATUS', 'QUEUE_STATUS', 'OTHER')),
    CONSTRAINT ck_observation_payload CHECK (
        CHAR_LENGTH(TRIM(schema_name)) > 0 AND schema_version >= 1 AND JSON_TYPE(payload) = 'OBJECT'),
    CONSTRAINT ck_observation_summary CHECK (CHAR_LENGTH(TRIM(summary)) > 0),
    CONSTRAINT ck_observation_window CHECK (
        (window_start IS NULL AND window_end IS NULL)
        OR (window_start IS NOT NULL AND window_end IS NOT NULL AND window_start <= window_end))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 待验证原因（04 §27～§28）：唯一允许改变当前状态的调查对象，历史变化进时间线
CREATE TABLE hypothesis (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    investigation_id  BIGINT UNSIGNED NOT NULL,
    title             VARCHAR(200)    NOT NULL,
    description       VARCHAR(2000)   NULL,
    status            VARCHAR(32)     NOT NULL,
    created_at        DATETIME(3)     NOT NULL,
    updated_at        DATETIME(3)     NOT NULL,
    lock_version      BIGINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    -- Evidence/Diagnosis 复合外键目标
    CONSTRAINT uk_hypothesis_id_investigation UNIQUE (id, investigation_id),
    INDEX idx_hypothesis_investigation_status (investigation_id, status),
    CONSTRAINT fk_hypothesis_investigation FOREIGN KEY (investigation_id)
        REFERENCES investigation (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_hypothesis_title CHECK (CHAR_LENGTH(TRIM(title)) > 0),
    CONSTRAINT ck_hypothesis_status CHECK (
        CAST(status AS BINARY) IN ('PENDING', 'SUPPORTED', 'INSUFFICIENT_EVIDENCE', 'REFUTED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Observation × Hypothesis 不可变关系（04 §29～§31）：无内容版本字段、无前一版本外键
CREATE TABLE evidence (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    investigation_id  BIGINT UNSIGNED NOT NULL,
    observation_id    BIGINT UNSIGNED NOT NULL,
    hypothesis_id     BIGINT UNSIGNED NOT NULL,
    relation          VARCHAR(16)     NOT NULL,
    reason            VARCHAR(1000)   NOT NULL,
    created_at        DATETIME(3)     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_evidence_observation_hypothesis UNIQUE (observation_id, hypothesis_id),
    -- Diagnosis 引用的复合外键目标（同一 Investigation）
    CONSTRAINT uk_evidence_id_investigation UNIQUE (id, investigation_id),
    INDEX idx_evidence_investigation (investigation_id, id),
    INDEX idx_evidence_hypothesis (hypothesis_id),
    CONSTRAINT fk_evidence_investigation FOREIGN KEY (investigation_id)
        REFERENCES investigation (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    -- 两侧必须属于同一 Investigation；恢复 Observation 的 investigation_id 为 NULL，无法被引用（04 §31）
    CONSTRAINT fk_evidence_observation FOREIGN KEY (observation_id, investigation_id)
        REFERENCES observation (id, investigation_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_evidence_hypothesis FOREIGN KEY (hypothesis_id, investigation_id)
        REFERENCES hypothesis (id, investigation_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_evidence_relation CHECK (CAST(relation AS BINARY) IN ('SUPPORTS', 'REFUTES', 'CONTEXT')),
    CONSTRAINT ck_evidence_reason CHECK (CHAR_LENGTH(TRIM(reason)) > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 版本化诊断（04 §32～§35）：不可修改，新判断新增版本
CREATE TABLE diagnosis (
    id                     BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    investigation_id       BIGINT UNSIGNED NOT NULL,
    run_no                 INT UNSIGNED    NOT NULL,
    version_no             INT UNSIGNED    NOT NULL,
    conclusion_type        VARCHAR(32)     NOT NULL,
    primary_hypothesis_id  BIGINT UNSIGNED NULL,
    summary                VARCHAR(2000)   NOT NULL,
    impact_summary         VARCHAR(1000)   NOT NULL,
    termination_reason     VARCHAR(64)     NULL,
    created_at             DATETIME(3)     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_diagnosis_investigation_version UNIQUE (investigation_id, version_no),
    CONSTRAINT uk_diagnosis_id_investigation UNIQUE (id, investigation_id),
    CONSTRAINT fk_diagnosis_investigation FOREIGN KEY (investigation_id)
        REFERENCES investigation (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    -- 主假设必须属于同一 Investigation（04 §34）
    CONSTRAINT fk_diagnosis_primary_hypothesis FOREIGN KEY (primary_hypothesis_id, investigation_id)
        REFERENCES hypothesis (id, investigation_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_diagnosis_run_no CHECK (run_no >= 1),
    CONSTRAINT ck_diagnosis_version_no CHECK (version_no >= 1),
    CONSTRAINT ck_diagnosis_conclusion CHECK (
        CAST(conclusion_type AS BINARY) IN ('PRIMARY_CAUSE_IDENTIFIED', 'POSSIBLE_CAUSE', 'UNDETERMINED')),
    -- 前两类必须有主假设；“至少一条关联主假设的 SUPPORTS Evidence”为跨表条件，由 Java 创建事务保证
    CONSTRAINT ck_diagnosis_primary_hypothesis CHECK (
        CAST(conclusion_type AS BINARY) = 'UNDETERMINED' OR primary_hypothesis_id IS NOT NULL),
    CONSTRAINT ck_diagnosis_summary CHECK (CHAR_LENGTH(TRIM(summary)) > 0),
    CONSTRAINT ck_diagnosis_impact_summary CHECK (CHAR_LENGTH(TRIM(impact_summary)) > 0),
    -- 取值集合由 Java 定义（04 §35 为示例，TASK-025/042），库内只约束格式
    CONSTRAINT ck_diagnosis_termination_reason CHECK (
        termination_reason IS NULL OR REGEXP_LIKE(termination_reason, '^[A-Z][A-Z0-9_]*\\z', 'c'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Diagnosis 冻结引用的具体 Evidence（04 §36）
CREATE TABLE diagnosis_evidence_ref (
    diagnosis_id  BIGINT UNSIGNED NOT NULL,
    evidence_id   BIGINT UNSIGNED NOT NULL,
    created_at    DATETIME(3)     NOT NULL,
    PRIMARY KEY (diagnosis_id, evidence_id),
    INDEX idx_diagnosis_evidence_ref_evidence (evidence_id),
    CONSTRAINT fk_diagnosis_evidence_ref_diagnosis FOREIGN KEY (diagnosis_id)
        REFERENCES diagnosis (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_diagnosis_evidence_ref_evidence FOREIGN KEY (evidence_id)
        REFERENCES evidence (id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- AI Runtime 运行记录（04 §59～§61）：内部技术记录；不保存完整 Prompt、原始日志或凭据
CREATE TABLE agent_step_record (
    id                       BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    incident_id              BIGINT UNSIGNED NOT NULL,
    investigation_id         BIGINT UNSIGNED NOT NULL,
    run_no                   INT UNSIGNED    NOT NULL,
    step_no                  INT UNSIGNED    NOT NULL,
    -- 结果返回前或输出不合法时可为空
    intent_type              VARCHAR(32)     NULL,
    status                   VARCHAR(16)     NOT NULL,
    model_provider           VARCHAR(64)     NULL,
    model_name               VARCHAR(128)    NULL,
    prompt_template_version  VARCHAR(64)     NULL,
    context_digest           VARCHAR(128)    NULL,
    output_schema_version    INT UNSIGNED    NULL,
    output_payload           JSON            NULL,
    latency_ms               BIGINT UNSIGNED NULL,
    prompt_tokens            INT UNSIGNED    NULL,
    completion_tokens        INT UNSIGNED    NULL,
    error_code               VARCHAR(64)     NULL,
    error_message            VARCHAR(1000)   NULL,
    started_at               DATETIME(3)     NOT NULL,
    finished_at              DATETIME(3)     NULL,
    created_at               DATETIME(3)     NOT NULL,
    updated_at               DATETIME(3)     NOT NULL,
    PRIMARY KEY (id),
    -- step_no 在整个 Investigation 内单调，跨 run 不重置（04 §59、§61）
    CONSTRAINT uk_agent_step_record_investigation_step UNIQUE (investigation_id, step_no),
    INDEX idx_agent_step_record_incident (incident_id, id),
    CONSTRAINT fk_agent_step_record_incident FOREIGN KEY (incident_id)
        REFERENCES incident (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_agent_step_record_investigation FOREIGN KEY (investigation_id, incident_id)
        REFERENCES investigation (id, incident_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_agent_step_record_run_no CHECK (run_no >= 1),
    CONSTRAINT ck_agent_step_record_step_no CHECK (step_no >= 1),
    -- 03 §2 冻结 Intent：调查五类＋诊断后的 PROPOSE_REMEDIATION
    CONSTRAINT ck_agent_step_record_intent CHECK (intent_type IS NULL OR CAST(intent_type AS BINARY) IN
        ('REQUEST_CAPABILITY', 'PROPOSE_HYPOTHESIS', 'UPDATE_HYPOTHESIS', 'PROPOSE_EVIDENCE_LINK',
         'COMPLETE_INVESTIGATION', 'PROPOSE_REMEDIATION')),
    CONSTRAINT ck_agent_step_record_status CHECK (CAST(status AS BINARY) IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    -- 终态才有结束时间；失败必须有错误码（进程中断记 PROCESS_INTERRUPTED）
    CONSTRAINT ck_agent_step_record_outcome CHECK (
        (CAST(status AS BINARY) = 'RUNNING' AND finished_at IS NULL AND error_code IS NULL)
        OR (CAST(status AS BINARY) = 'SUCCEEDED' AND finished_at IS NOT NULL AND error_code IS NULL)
        OR (CAST(status AS BINARY) = 'FAILED' AND finished_at IS NOT NULL AND error_code IS NOT NULL)),
    CONSTRAINT ck_agent_step_record_output CHECK ((
        (output_schema_version IS NULL AND output_payload IS NULL)
        OR (output_schema_version >= 1 AND JSON_TYPE(output_payload) = 'OBJECT')) IS TRUE),
    CONSTRAINT ck_agent_step_record_error_code CHECK (
        error_code IS NULL OR REGEXP_LIKE(error_code, '^[A-Z][A-Z0-9_]*\\z', 'c'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
