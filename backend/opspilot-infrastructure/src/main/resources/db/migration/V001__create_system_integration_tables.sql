-- 系统接入配置（04 §6～§11）。
-- 时间一律由应用层按 UTC 写入 DATETIME(3)，不使用数据库默认时间；外键统一 RESTRICT，配置用状态而非物理删除（04 §3～§5）。
-- 枚举 CHECK 用 CAST(... AS BINARY) 逐字节比较：列默认 ai_ci 放行大小写/重音变体，utf8mb4_bin 为 PAD SPACE 放行末尾空格，
-- utf8mb4_0900_bin 需 8.0.17+，而基线为 8.0.16+；正则用 \z 锚定整串（ICU 的 $ 允许末尾换行），且 'c' 区分大小写；capability_key 是否存在由 Java CapabilityRegistry 判定（04 §11），此处只校验格式。

CREATE TABLE managed_system (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    system_key    VARCHAR(64)     NOT NULL,
    name          VARCHAR(128)    NOT NULL,
    description   VARCHAR(1024)   NULL,
    environment   VARCHAR(32)     NOT NULL,
    status        VARCHAR(16)     NOT NULL,
    created_at    DATETIME(3)     NOT NULL,
    updated_at    DATETIME(3)     NOT NULL,
    lock_version  BIGINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_managed_system_key UNIQUE (system_key),
    CONSTRAINT ck_managed_system_key CHECK (REGEXP_LIKE(system_key, '^[a-z0-9][a-z0-9-]*\\z', 'c')),
    CONSTRAINT ck_managed_system_name CHECK (CHAR_LENGTH(TRIM(name)) > 0),
    CONSTRAINT ck_managed_system_environment CHECK (CHAR_LENGTH(TRIM(environment)) > 0),
    CONSTRAINT ck_managed_system_status CHECK (CAST(status AS BINARY) IN ('ACTIVE', 'DISABLED', 'ARCHIVED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE managed_resource (
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    managed_system_id  BIGINT UNSIGNED NOT NULL,
    resource_key       VARCHAR(64)     NOT NULL,
    name               VARCHAR(128)    NOT NULL,
    resource_type      VARCHAR(32)     NOT NULL,
    description        VARCHAR(1024)   NULL,
    status             VARCHAR(16)     NOT NULL,
    created_at         DATETIME(3)     NOT NULL,
    updated_at         DATETIME(3)     NOT NULL,
    lock_version       BIGINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_managed_resource_system_key UNIQUE (managed_system_id, resource_key),
    INDEX idx_managed_resource_system_status (managed_system_id, status),
    CONSTRAINT fk_managed_resource_system FOREIGN KEY (managed_system_id)
        REFERENCES managed_system (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_managed_resource_key CHECK (REGEXP_LIKE(resource_key, '^[a-z0-9][a-z0-9-]*\\z', 'c')),
    CONSTRAINT ck_managed_resource_name CHECK (CHAR_LENGTH(TRIM(name)) > 0),
    CONSTRAINT ck_managed_resource_type CHECK (CAST(resource_type AS BINARY) IN
        ('SERVICE', 'DATABASE', 'CACHE', 'MESSAGE_QUEUE', 'CONSUMER', 'EXTERNAL_DEPENDENCY')),
    CONSTRAINT ck_managed_resource_status CHECK (CAST(status AS BINARY) IN ('ACTIVE', 'DISABLED', 'ARCHIVED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE data_source_connection (
    id                     BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    connection_key         VARCHAR(64)     NOT NULL,
    name                   VARCHAR(128)    NOT NULL,
    provider_type          VARCHAR(32)     NOT NULL,
    endpoint               VARCHAR(512)    NOT NULL,
    credential_ref         VARCHAR(255)    NULL,
    config_schema_name     VARCHAR(128)    NOT NULL,
    config_schema_version  INT UNSIGNED    NOT NULL,
    config_payload         JSON            NOT NULL,
    status                 VARCHAR(16)     NOT NULL,
    created_at             DATETIME(3)     NOT NULL,
    updated_at             DATETIME(3)     NOT NULL,
    lock_version           BIGINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_data_source_connection_key UNIQUE (connection_key),
    CONSTRAINT ck_data_source_connection_key CHECK (REGEXP_LIKE(connection_key, '^[a-z0-9][a-z0-9-]*\\z', 'c')),
    CONSTRAINT ck_data_source_connection_name CHECK (CHAR_LENGTH(TRIM(name)) > 0),
    -- 06 各能力声明的 Provider：metrics.query→PROMETHEUS、logs.search→LOKI、cache/queue→REDIS、database→MYSQL、service→DOCKER
    CONSTRAINT ck_data_source_connection_provider CHECK (CAST(provider_type AS BINARY) IN
        ('PROMETHEUS', 'LOKI', 'REDIS', 'MYSQL', 'DOCKER')),
    CONSTRAINT ck_data_source_connection_endpoint CHECK (CHAR_LENGTH(TRIM(endpoint)) > 0),
    -- 只保存引用，不保存明文凭据；V0.1 SecretResolver 仅支持 env://（07 §63）
    CONSTRAINT ck_data_source_connection_credential_ref CHECK (
        credential_ref IS NULL OR REGEXP_LIKE(credential_ref, '^env://[A-Z_][A-Z0-9_]*\\z', 'c')),
    CONSTRAINT ck_data_source_connection_schema_name CHECK (CHAR_LENGTH(TRIM(config_schema_name)) > 0),
    CONSTRAINT ck_data_source_connection_schema_version CHECK (config_schema_version >= 1),
    CONSTRAINT ck_data_source_connection_payload CHECK (JSON_TYPE(config_payload) = 'OBJECT'),
    CONSTRAINT ck_data_source_connection_status CHECK (CAST(status AS BINARY) IN ('ACTIVE', 'DISABLED', 'ARCHIVED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE resource_binding (
    id                         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    managed_resource_id        BIGINT UNSIGNED NOT NULL,
    data_source_connection_id  BIGINT UNSIGNED NOT NULL,
    selector_schema_name       VARCHAR(128)    NOT NULL,
    selector_schema_version    INT UNSIGNED    NOT NULL,
    selector_payload           JSON            NOT NULL,
    created_at                 DATETIME(3)     NOT NULL,
    updated_at                 DATETIME(3)     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_resource_binding_resource_connection UNIQUE (managed_resource_id, data_source_connection_id),
    INDEX idx_resource_binding_connection (data_source_connection_id),
    CONSTRAINT fk_resource_binding_resource FOREIGN KEY (managed_resource_id)
        REFERENCES managed_resource (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_resource_binding_connection FOREIGN KEY (data_source_connection_id)
        REFERENCES data_source_connection (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_resource_binding_schema_name CHECK (CHAR_LENGTH(TRIM(selector_schema_name)) > 0),
    CONSTRAINT ck_resource_binding_schema_version CHECK (selector_schema_version >= 1),
    CONSTRAINT ck_resource_binding_payload CHECK (JSON_TYPE(selector_payload) = 'OBJECT')
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE capability_binding (
    id                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    managed_resource_id  BIGINT UNSIGNED NOT NULL,
    capability_key       VARCHAR(64)     NOT NULL,
    enabled              BOOLEAN         NOT NULL,
    created_at           DATETIME(3)     NOT NULL,
    updated_at           DATETIME(3)     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_capability_binding_resource_capability UNIQUE (managed_resource_id, capability_key),
    CONSTRAINT fk_capability_binding_resource FOREIGN KEY (managed_resource_id)
        REFERENCES managed_resource (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_capability_binding_key CHECK (REGEXP_LIKE(capability_key, '^[a-z]+[.][a-z]+\\z', 'c')),
    CONSTRAINT ck_capability_binding_enabled CHECK (enabled IN (0, 1))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
