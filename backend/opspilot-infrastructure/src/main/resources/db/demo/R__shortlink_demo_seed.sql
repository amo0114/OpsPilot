-- ShortLink Demo 系统接入配置（06 §131、08 TASK-010）与 S3 恢复策略（08 TASK-076）。只含配置数据，不含 Fault 或 Incident。
-- 仅 demo profile 加载（07 §93）：db/demo 不在默认 Flyway locations 中。
-- 可重复迁移：每次内容变化后在全部版本化迁移之后重跑；按唯一键 upsert，重跑收敛到本文件内容。
-- 不使用已弃用的 VALUES()（且需兼容 8.0.16），ON DUPLICATE KEY UPDATE 引用 SELECT 派生表列。
-- 本文件只 upsert，从这里移除的行不会被自动删除。
-- 端点、标签与 PromQL 模板为 Demo 约定值，TASK-052/105/106 按真实靶场校准。容器名与 Stream 键/消费组已按 ShortLink 实际实现校准（TASK-093：
-- RedisKeyConstant 的 short-link:stats-stream / short-link:stats-stream:only-group，deploy/demo 的 shortlink-statistics-consumer）。
-- 凭据只写 env:// 引用（03 §12、07 §63）；选择器使用 TASK-008 注册的 <provider>.resource.binding / 1。

INSERT INTO managed_system (system_key, name, description, environment, status, created_at, updated_at)
SELECT src.system_key, src.name, src.description, src.environment, 'ACTIVE', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)
FROM (SELECT 'shortlink-platform' AS system_key, 'ShortLink Platform' AS name, '短链接业务系统' AS description,
             'DEMO' AS environment) src
ON DUPLICATE KEY UPDATE
    name = src.name, description = src.description, environment = src.environment, status = 'ACTIVE',
    updated_at = UTC_TIMESTAMP(3);

INSERT INTO managed_resource (managed_system_id, resource_key, name, resource_type, description, status,
                              created_at, updated_at)
SELECT s.id, src.resource_key, src.name, src.resource_type, src.description, 'ACTIVE', UTC_TIMESTAMP(3),
       UTC_TIMESTAMP(3)
FROM (SELECT 'redirect-service' AS resource_key, 'ShortLink Redirect Service' AS name, 'SERVICE' AS resource_type,
             '短链接跳转与业务 API（project-api 容器）' AS description
      UNION ALL SELECT 'statistics-consumer', 'Statistics Consumer', 'CONSUMER', '访问统计 Redis Stream 消费者'
      UNION ALL SELECT 'shortlink-redis', 'ShortLink Redis', 'CACHE', '短链接缓存 Redis'
      UNION ALL SELECT 'shortlink-mysql', 'ShortLink MySQL', 'DATABASE', '短链接业务数据库'
      UNION ALL SELECT 'statistics-stream', 'Statistics Stream', 'MESSAGE_QUEUE', '访问统计 Redis Stream') src
JOIN managed_system s ON s.system_key = 'shortlink-platform'
ON DUPLICATE KEY UPDATE
    name = src.name, resource_type = src.resource_type, description = src.description, status = 'ACTIVE',
    updated_at = UTC_TIMESTAMP(3);

INSERT INTO data_source_connection (connection_key, name, provider_type, endpoint, credential_ref, config_schema_name,
                                    config_schema_version, config_payload, status, created_at, updated_at)
SELECT src.connection_key, src.name, src.provider_type, src.endpoint, src.credential_ref, src.config_schema_name, 1,
       src.config_payload, 'ACTIVE', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)
FROM (SELECT 'prometheus-local' AS connection_key, 'Local Prometheus' AS name, 'PROMETHEUS' AS provider_type,
             'http://prometheus:9090' AS endpoint, CAST(NULL AS CHAR(255)) AS credential_ref,
             'prometheus.connection.config' AS config_schema_name, JSON_OBJECT() AS config_payload
      UNION ALL SELECT 'loki-local', 'Local Loki', 'LOKI', 'http://loki:3100', NULL, 'loki.connection.config',
                       JSON_OBJECT()
      -- OpsPilot 经 Toxiproxy 访问 Redis（09 §8、§9），账号受 Redis ACL 限制为只读诊断命令（06 §63）
      UNION ALL SELECT 'redis-local', 'Local Redis (via proxy)', 'REDIS', 'redis://redis-proxy:6379',
                       'env://OPSPILOT_SHORTLINK_REDIS_PASSWORD', 'redis.connection.config',
                       JSON_OBJECT('username', 'opspilot_observer')
      -- 只读调查账号（06 §73）
      UNION ALL SELECT 'mysql-readonly', 'ShortLink MySQL (read-only)', 'MYSQL', 'mysql://mysql:3306',
                       'env://OPSPILOT_SHORTLINK_MYSQL_PASSWORD', 'mysql.connection.config',
                       JSON_OBJECT('username', 'opspilot_readonly')
      UNION ALL SELECT 'docker-local', 'Local Docker Engine', 'DOCKER', 'unix:///var/run/docker.sock', NULL,
                       'docker.connection.config', JSON_OBJECT()) src
ON DUPLICATE KEY UPDATE
    name = src.name, provider_type = src.provider_type, endpoint = src.endpoint, credential_ref = src.credential_ref,
    config_schema_name = src.config_schema_name, config_schema_version = 1, config_payload = src.config_payload,
    status = 'ACTIVE', updated_at = UTC_TIMESTAMP(3);

-- redirect-service 的 8 个语义指标（09 §7）；模板为完整 PromQL，查询时间范围由 Provider 按 windowKey 提供（06 §42）
INSERT INTO resource_binding (managed_resource_id, data_source_connection_id, selector_schema_name,
                              selector_schema_version, selector_payload, created_at, updated_at)
SELECT r.id, c.id, src.selector_schema_name, 1, src.selector_payload, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)
FROM (SELECT 'redirect-service' AS resource_key, 'prometheus-local' AS connection_key,
             'prometheus.resource.binding' AS selector_schema_name,
             JSON_OBJECT(
                 'labels', JSON_OBJECT('application', 'shortlink-project'),
                 'metrics', JSON_OBJECT(
                     'http.request.latency.p99', JSON_OBJECT(
                         'queryTemplate', 'histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket{application="shortlink-project"}[1m]))) * 1000',
                         'unit', 'ms'),
                     'http.request.error_rate', JSON_OBJECT(
                         'queryTemplate', 'sum(rate(http_server_requests_seconds_count{application="shortlink-project",status=~"5.."}[1m])) / sum(rate(http_server_requests_seconds_count{application="shortlink-project"}[1m]))',
                         'unit', 'ratio'),
                     'http.request.rate', JSON_OBJECT(
                         'queryTemplate', 'sum(rate(http_server_requests_seconds_count{application="shortlink-project"}[1m]))',
                         'unit', 'req/s'),
                     'jvm.cpu.usage', JSON_OBJECT(
                         'queryTemplate', 'max(process_cpu_usage{application="shortlink-project"})',
                         'unit', 'ratio'),
                     'jvm.memory.heap.usage', JSON_OBJECT(
                         'queryTemplate', 'sum(jvm_memory_used_bytes{application="shortlink-project",area="heap"}) / sum(jvm_memory_max_bytes{application="shortlink-project",area="heap"})',
                         'unit', 'ratio'),
                     'db.pool.active', JSON_OBJECT(
                         'queryTemplate', 'sum(hikaricp_connections_active{application="shortlink-project"})',
                         'unit', 'connections'),
                     'db.pool.pending', JSON_OBJECT(
                         'queryTemplate', 'sum(hikaricp_connections_pending{application="shortlink-project"})',
                         'unit', 'connections'),
                     'db.pool.max', JSON_OBJECT(
                         'queryTemplate', 'sum(hikaricp_connections_max{application="shortlink-project"})',
                         'unit', 'connections'))) AS selector_payload
      UNION ALL SELECT 'redirect-service', 'loki-local', 'loki.resource.binding',
                       JSON_OBJECT('labels', JSON_OBJECT('app', 'shortlink-project'))
      UNION ALL SELECT 'statistics-consumer', 'docker-local', 'docker.resource.binding',
                       JSON_OBJECT('containerName', 'shortlink-statistics-consumer')
      UNION ALL SELECT 'statistics-consumer', 'loki-local', 'loki.resource.binding',
                       JSON_OBJECT('labels', JSON_OBJECT('app', 'shortlink-statistics-consumer'))
      -- 缓存资源无需选择器
      UNION ALL SELECT 'shortlink-redis', 'redis-local', 'redis.resource.binding', JSON_OBJECT()
      UNION ALL SELECT 'statistics-stream', 'redis-local', 'redis.resource.binding',
                       JSON_OBJECT('streamKey', 'short-link:stats-stream', 'consumerGroup', 'short-link:stats-stream:only-group')
      UNION ALL SELECT 'shortlink-mysql', 'mysql-readonly', 'mysql.resource.binding',
                       JSON_OBJECT('databaseName', 'shortlink')) src
JOIN managed_system s ON s.system_key = 'shortlink-platform'
JOIN managed_resource r ON r.managed_system_id = s.id AND r.resource_key = src.resource_key
JOIN data_source_connection c ON c.connection_key = src.connection_key
ON DUPLICATE KEY UPDATE
    selector_schema_name = src.selector_schema_name, selector_schema_version = 1,
    selector_payload = src.selector_payload, updated_at = UTC_TIMESTAMP(3);

INSERT INTO capability_binding (managed_resource_id, capability_key, enabled, created_at, updated_at)
SELECT r.id, src.capability_key, TRUE, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)
FROM (SELECT 'redirect-service' AS resource_key, 'metrics.query' AS capability_key
      UNION ALL SELECT 'redirect-service', 'logs.search'
      UNION ALL SELECT 'statistics-consumer', 'service.inspect'
      UNION ALL SELECT 'statistics-consumer', 'logs.search'
      UNION ALL SELECT 'statistics-consumer', 'service.restart'
      UNION ALL SELECT 'shortlink-redis', 'cache.inspect'
      UNION ALL SELECT 'shortlink-mysql', 'database.inspect'
      UNION ALL SELECT 'statistics-stream', 'queue.inspect') src
JOIN managed_system s ON s.system_key = 'shortlink-platform'
JOIN managed_resource r ON r.managed_system_id = s.id AND r.resource_key = src.resource_key
ON DUPLICATE KEY UPDATE enabled = TRUE, updated_at = UTC_TIMESTAMP(3);

-- S3 恢复合同（09 §75、06 §113，08 TASK-076）：挂在 statistics-consumer，执行顺序 B -> C -> D -> A，四项均 required。
-- 与 Java 激活校验一致的条件由 ShortLinkDemoSeedTest 用正式 Codec 与 RecoveryPolicyValidator 复核。
-- 策略版本不可改写（01 §28）：只在该资源还没有任何策略时插入 v1 ACTIVE，重跑不修改已有版本，也不会产生第二个 ACTIVE；
-- 阈值等校准须经激活服务生成新版本并记录依据，不改本行后期望覆盖。pendingCount 阈值 20 为 Demo 可配置默认值（09 §75）。
INSERT INTO recovery_policy (managed_resource_id, policy_key, name, version_no, criteria_schema_name,
                             criteria_schema_version, criteria_payload, status, created_at, activated_at)
SELECT r.id, 'statistics-consumer-recovery', '统计消费者恢复标准', 1, 'recovery.policy.criteria', 1,
       CAST('{"schemaName":"recovery.policy.criteria","schemaVersion":1,"maxDurationSeconds":120,"maxSampleAgeSeconds":120,"criteria":[{"criterionKey":"stream-lag-decreasing","name":"未投递积压进入并保持健康区间","capabilityKey":"queue.inspect","targetResourceKey":"statistics-stream","arguments":{},"sampling":{"sampleCount":4,"intervalSeconds":10,"maxGapSeconds":20},"predicate":{"type":"MONOTONIC_TREND","field":"lag","direction":"DECREASING","healthyThreshold":20,"requireFinalHealthy":true},"required":true},{"criterionKey":"stream-lag-drained","name":"末次未投递积压达标","capabilityKey":"queue.inspect","targetResourceKey":"statistics-stream","arguments":{},"sampling":{"sampleCount":1,"intervalSeconds":0,"maxGapSeconds":null},"predicate":{"type":"NUMERIC_COMPARE","field":"lag","operator":"LTE","value":20},"required":true},{"criterionKey":"stream-pending-healthy","name":"已投递未确认积压保持健康","capabilityKey":"queue.inspect","targetResourceKey":"statistics-stream","arguments":{},"sampling":{"sampleCount":2,"intervalSeconds":5,"maxGapSeconds":10},"predicate":{"type":"NUMERIC_COMPARE","field":"pendingCount","operator":"LTE","value":20},"required":true},{"criterionKey":"consumer-running","name":"消费者持续运行","capabilityKey":"service.inspect","targetResourceKey":"statistics-consumer","arguments":{},"sampling":{"sampleCount":2,"intervalSeconds":5,"maxGapSeconds":10},"predicate":{"type":"FIELD_EQUALS","field":"runtimeState","value":"RUNNING"},"required":true}]}' AS JSON),
       'ACTIVE', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)
FROM managed_resource r
JOIN managed_system s ON s.id = r.managed_system_id AND s.system_key = 'shortlink-platform'
WHERE r.resource_key = 'statistics-consumer'
  AND NOT EXISTS (SELECT 1 FROM recovery_policy p WHERE p.managed_resource_id = r.id);
