-- 用户基线四项问题修复迁移脚本（MySQL 8.0+）
-- 仅提供DDL，不会由程序自动执行；执行前请按目标环境完成备份和变更审核。

ALTER TABLE `ra_calendar_day`
    MODIFY COLUMN `holiday_group_code` VARCHAR(64) DEFAULT NULL COMMENT '连休假期组编码，允许人工维护',
    ADD COLUMN `festival_tags` VARCHAR(256) DEFAULT NULL COMMENT '具体日期的节日标签，多个标签使用英文逗号分隔'
        AFTER `holiday_group_code`;

ALTER TABLE `ra_user_baseline_detail`
    ADD COLUMN `sync_status` VARCHAR(16) NOT NULL DEFAULT 'SYNCED'
        COMMENT 'TDengine同步状态：PENDING/SYNCED' AFTER `corrected_at`,
    ADD COLUMN `synced_at` DATETIME(3) DEFAULT NULL COMMENT '最近一次TDengine同步完成时间'
        AFTER `sync_status`,
    ADD KEY `idx_user_baseline_sync` (`data_date`, `slot_no`, `sync_status`, `id`);

CREATE TABLE IF NOT EXISTS `ra_user_baseline_initial_window` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `cons_no` VARCHAR(64) NOT NULL COMMENT '户号',
    `calc_version` VARCHAR(32) NOT NULL COMMENT '算法版本',
    `first_target_time` DATETIME NOT NULL COMMENT '首次需要初始化的修正目标时刻',
    `seed_window_json` JSON NOT NULL COMMENT '按完整历史时点保存的16点B0及样本来源',
    `initialized_at` DATETIME(3) NOT NULL COMMENT '首次初始化完成时间',
    `remark` VARCHAR(1000) NOT NULL COMMENT '中文初始化说明',
    `create_time` DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `create_by` BIGINT DEFAULT NULL COMMENT '创建人ID',
    `create_name` VARCHAR(64) DEFAULT NULL COMMENT '创建人姓名',
    `last_modify_time` DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后修改时间',
    `last_modify_by` BIGINT DEFAULT NULL COMMENT '更新人ID',
    `last_modify_name` VARCHAR(64) DEFAULT NULL COMMENT '更新人姓名',
    `deleted` BIT(1) NOT NULL DEFAULT b'0' COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_baseline_initial_window` (`cons_no`, `calc_version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户首次基线初始化窗口';
