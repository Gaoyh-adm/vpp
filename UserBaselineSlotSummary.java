package com.vpp.platform.resource.baseline.model;

import lombok.Data;

/** 用户基线待修正点位统计。 */
@Data
public class UserBaselineSlotSummary {

    private Integer slotNo;
    private Integer totalCount;
    private Integer waitingCount;
}
