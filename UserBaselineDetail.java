package com.vpp.platform.resource.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.vpp.framework.commons.entity.SuperEntity;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 用户逐点基线计算审计明细。 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("ra_user_baseline_detail")
public class UserBaselineDetail extends SuperEntity<Long> {

    private Long runId;
    private LocalDate dataDate;
    private Integer slotNo;
    private LocalDateTime targetTime;
    private String consNo;
    private String unitId;
    private String dayType;
    private String sampleDetailJson;
    private BigDecimal initialMeanMw;
    private BigDecimal thresholdMw;
    private String removedSampleJson;
    private Integer removedCount;
    private BigDecimal rawBaselineCalcMw;
    private BigDecimal rawBaselineMw;
    private Boolean needCorrection;
    private String actualWindowJson;
    private String baselineWindowJson;
    private BigDecimal actualWindowMeanMw;
    private BigDecimal baselineWindowMeanMw;
    private BigDecimal kValue;
    private BigDecimal finalBaselineMw;
    private String correctionStatus;
    private String calculationRemark;
    private String traceCode;
    private LocalDateTime snapshotTime;
    private LocalDateTime generatedAt;
    private LocalDateTime correctedAt;
    private String syncStatus;
    private LocalDateTime syncedAt;
    private String calcVersion;
}
