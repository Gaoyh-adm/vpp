package com.vpp.platform.resource.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.sql.Timestamp;

/**
 * 聚合单元基线负荷（TDengine 超级表 st_unit_baseline_load）.
 * <p>每天定时任务按单元生成 96 点基线负荷；TAGS 仅 unit_id（子表按单元建），data_date 为普通列。
 * 参照 VehicleStationPower 的 TDengine 实体写法。</p>
 * <pre>{@code
 *   COLUMNS: ts, data_date, load_mw, calc_method, fit_source
 *   TAGS:    unit_id
 * }</pre>
 *
 * @author VPP Platform
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("st_unit_baseline_load")
public class UnitBaselineLoad implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 子表名: unit_baseline_load_{unitId 非法字符改下划线} */
    @TableField(value = "`tbname`")
    private String tbname;

    /** 15 分钟时段（slot 1=00:15 ... slot 96=次日00:00） */
    @TableId(type = IdType.INPUT)
    private Timestamp ts;

    /** 运行日 D (yyyy-MM-dd) */
    private String dataDate;

    /** 基线负荷 (MW) */
    private Double loadMw;

    /** 计算方式: LAST5_WORKDAY / LAST5_WEEKEND */
    private String calcMethod;

    /** 拟合来源（暂不填充） */
    private String fitSource;

    /** 聚合单元编号（TAG） */
    private String unitId;
}
