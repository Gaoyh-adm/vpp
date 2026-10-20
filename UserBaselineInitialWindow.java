package com.vpp.platform.resource.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.vpp.framework.commons.entity.SuperEntity;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/** 用户首次修正时保存的历史基线初始化窗口。 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("ra_user_baseline_initial_window")
public class UserBaselineInitialWindow extends SuperEntity<Long> {

    private String consNo;
    private String calcVersion;
    private LocalDateTime firstTargetTime;
    private String seedWindowJson;
    private LocalDateTime initializedAt;
    private String remark;
}
