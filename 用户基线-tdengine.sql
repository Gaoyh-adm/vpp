-- ============================================================
-- 用户基线结果超级表（TDengine 3.2.7+）
-- 说明：只创建新超级表，不修改任何现有表和数据
-- 六位小数值统一放大10^6后使用BIGINT保存，避免DOUBLE精度损失
-- ============================================================

USE vpp_resource;

CREATE STABLE IF NOT EXISTS `st_user_baseline_load` (
    `ts` TIMESTAMP,
    `slot_no` TINYINT,
    `raw_baseline_e6` BIGINT,
    `actual_window_mean_e6` BIGINT,
    `baseline_window_mean_e6` BIGINT,
    `k_e6` BIGINT,
    `final_baseline_e6` BIGINT,
    `correction_status` VARCHAR(32),
    `calculation_remark` NCHAR(2048),
    `trace_id` VARCHAR(64),
    `calc_version` VARCHAR(32),
    `generated_at` TIMESTAMP,
    `corrected_at` TIMESTAMP,
    `data_date` VARCHAR(10)
) TAGS (
    `cons_no` VARCHAR(64)
) COMMENT '营销户号96点基线结果';
