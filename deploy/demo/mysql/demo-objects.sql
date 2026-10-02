-- OpsPilot Demo 靶场专用的数据库对象（08 TASK-095、09 §44、§52）；只由 deploy/demo 的一次性初始化服务执行，ShortLink 开发库不含这些对象。
-- 可重复执行：过程先删后建，账号与授权幂等。

USE link;

DROP PROCEDURE IF EXISTS refresh_link_statistics_snapshot;

DELIMITER $$
-- 统计快照刷新：在数据库侧持续约 budget_ms 毫秒的真实读查询（不用 SLEEP），供 S2 演练经应用连接池调用
CREATE PROCEDURE refresh_link_statistics_snapshot(IN budget_ms INT)
BEGIN
    DECLARE started DATETIME(6) DEFAULT SYSDATE(6);
    DECLARE goto_rows BIGINT DEFAULT 0;
    WHILE TIMESTAMPDIFF(MICROSECOND, started, SYSDATE(6)) < budget_ms * 1000 DO
        SELECT COUNT(*) INTO goto_rows
        FROM t_link_goto_0 g0 CROSS JOIN t_link_goto_1 g1;
    END WHILE;
END$$
DELIMITER ;

-- Demo 控制账号：只能读取并清理语句摘要汇总（09 §52）；调查只读账号不授予任何清理权限
CREATE USER IF NOT EXISTS 'opspilot_fault_control'@'%' IDENTIFIED BY 'fault_control_local_only';
ALTER USER 'opspilot_fault_control'@'%' IDENTIFIED BY 'fault_control_local_only';
GRANT SELECT, DROP ON performance_schema.events_statements_summary_by_digest TO 'opspilot_fault_control'@'%';

-- 控制账号的会话不进入 Performance Schema 统计：清理与读取摘要的语句本身不留在 events_statements_summary_by_digest，
-- 被调查方（database.inspect）看不到控制痕迹。setup_actors 不跨 MySQL 重启保留，每次 Compose 启动由本脚本重新写入。
DELETE FROM performance_schema.setup_actors WHERE USER = 'opspilot_fault_control';
INSERT INTO performance_schema.setup_actors (HOST, USER, `ROLE`, ENABLED, HISTORY)
VALUES ('%', 'opspilot_fault_control', '%', 'NO', 'NO');
