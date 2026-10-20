-- 用户基线七项问题修复迁移脚本（MySQL 8.0+）
-- 增加每日运行及修正任务租约字段和索引，不会由程序自动执行，也不会修改现有业务结果。

ALTER TABLE `ra_user_baseline_run`
    ADD COLUMN `execution_owner` VARCHAR(64) DEFAULT NULL COMMENT '当前执行者唯一标识'
        AFTER `error_message`,
    ADD COLUMN `lease_expire_time` DATETIME(3) DEFAULT NULL COMMENT '当前执行租约到期时间'
        AFTER `execution_owner`,
    ADD COLUMN `heartbeat_time` DATETIME(3) DEFAULT NULL COMMENT '当前执行者最近心跳时间'
        AFTER `lease_expire_time`,
    ADD KEY `idx_user_baseline_run_lease` (`status`, `lease_expire_time`);

-- 历史RUNNING空租约只能在旧版本执行者全部停用后人工处置，本脚本不自动改状态。

ALTER TABLE `ra_user_baseline_correction_task`
    ADD COLUMN `execution_owner` VARCHAR(64) DEFAULT NULL COMMENT '当前执行者唯一标识'
        AFTER `calc_version`,
    ADD COLUMN `lease_expire_time` DATETIME(3) DEFAULT NULL COMMENT '当前执行租约到期时间'
        AFTER `execution_owner`,
    ADD COLUMN `heartbeat_time` DATETIME(3) DEFAULT NULL COMMENT '当前执行者最近心跳时间'
        AFTER `lease_expire_time`,
    ADD KEY `idx_user_baseline_task_lease` (`status`, `lease_expire_time`);
