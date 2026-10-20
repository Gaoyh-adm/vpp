package com.vpp.platform.resource.baseline.model;

import java.math.BigDecimal;
import java.util.List;

/** 单户单点原始基线计算结果。 */
public record RawBaselineResult(
        BigDecimal initialMean,
        BigDecimal threshold,
        BigDecimal rawCalculationValue,
        BigDecimal rawBaseline,
        List<TypicalSampleTrace> samples,
        List<TypicalSampleTrace> removedSamples,
        String status,
        String chineseRemark) {

    public boolean hasRemovedSample() {
        return removedSamples != null && !removedSamples.isEmpty();
    }
}

