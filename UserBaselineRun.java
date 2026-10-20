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

/** 用户基线每日运行记录。 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("ra_user_baseline_run")
public class UserBaselineRun extends SuperEntity<Long> {

    private LocalDate dataDate;
    private String calcVersion;
    private String status;
    private Integer totalUserCount;
    private Integer successUserCount;
    private Integer failedUserCount;
    private LocalDateTime snapshotTime;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private String errorMessage;
    private String executionOwner;
    private LocalDateTime leaseExpireTime;
    private LocalDateTime heartbeatTime;
}
