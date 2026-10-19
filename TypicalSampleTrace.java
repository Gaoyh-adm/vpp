package com.vpp.platform.resource.baseline.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 参与原始基线计算的一个典型日样本。 */
public record TypicalSampleTrace(
        LocalDate targetDate,
        LocalDate sourceDate,
        int slotNo,
        BigDecimal value,
        String sourceCategory,
        boolean fitted,
        String sourceType) {
}

