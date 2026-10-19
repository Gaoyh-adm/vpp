package com.vpp.platform.resource.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.vpp.framework.commons.entity.SuperEntity;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 用户基线时刻级修正任务。 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("ra_user_baseline_correction_task")
public class UserBaselineCorrectionTask extends SuperEntity<Long> {

    private LocalDate dataDate;
    private Integer slotNo;
    private LocalDateTime targetTime;
    private LocalDateTime scheduledTime;
    private String status;
    private Integer totalCount;
    private Integer successCount;
    private Integer failedCount;
    private Integer retryCount;
    private String lastError;
    private String calcVersion;
    private LocalDateTime completedAt;
}
