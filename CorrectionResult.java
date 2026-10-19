package com.vpp.platform.resource.baseline.model;

import java.math.BigDecimal;
import java.util.List;

/** 单户单点K值修正结果。 */
public record CorrectionResult(
        String status,
        BigDecimal actualMean,
        BigDecimal baselineMean,
        BigDecimal kValue,
        BigDecimal finalBaseline,
        List<WindowValueTrace> actualWindow,
        List<WindowValueTrace> baselineWindow,
        String chineseRemark) {
}

