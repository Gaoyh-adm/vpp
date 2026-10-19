package com.vpp.platform.resource.baseline.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** K值窗口中一个目标点的实际取值及来源。 */
public record WindowValueTrace(
        LocalDateTime targetTime,
        LocalDateTime sourceTime,
        BigDecimal value,
        String sourceDescription,
        String direction,
        int distanceMinutes) {
}

