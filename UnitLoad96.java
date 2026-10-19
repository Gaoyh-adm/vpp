package com.vpp.platform.resource.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.sql.Timestamp;

/**
 * 单元级 96 点实时负荷（TDengine 超级表 st_unit_load_96）.
 * <p>由 Flink 站级聚合结果落库（站→单元汇总），作为基线负荷计算的数据基础。
 * unit_id / data_date 为 TAG。</p>
 * <pre>{@code
 *   COLUMNS: ts, load_mw, src_type
 *   TAGS:    unit_id, data_date
 * }</pre>
 *
 * @author VPP Platform
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("st_unit_load_96")
public class UnitLoad96 implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 15 分钟时段 */
    @TableId(type = IdType.INPUT)
    private Timestamp ts;

    /** 负荷 (MW) */
    private Double loadMw;

    /** 数据来源类型 */
    private String srcType;

    /** 聚合单元编号（TAG） */
    private String unitId;

    /** 数据日期（TAG, yyyy-MM-dd） */
    private String dataDate;
}
