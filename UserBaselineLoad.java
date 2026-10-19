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

/** TDengine营销户号96点基线结果。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("st_user_baseline_load")
public class UserBaselineLoad implements Serializable {

    @TableField(value = "`tbname`", select = false)
    private String tbname;

    @TableId(type = IdType.INPUT)
    private Timestamp ts;

    private Integer slotNo;
    private Long rawBaselineE6;
    private Long actualWindowMeanE6;
    private Long baselineWindowMeanE6;
    private Long kE6;
    private Long finalBaselineE6;
    private String correctionStatus;
    private String calculationRemark;
    private String traceId;
    private String calcVersion;
    private Timestamp generatedAt;
    private Timestamp correctedAt;

    /** TDengine标签：户号 */
    private String consNo;

    /** 业务日期 */
    private String dataDate;
}
