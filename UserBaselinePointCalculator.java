package com.vpp.platform.resource.baseline;

import com.vpp.platform.resource.baseline.model.CorrectionResult;
import com.vpp.platform.resource.baseline.model.RawBaselineResult;
import com.vpp.platform.resource.baseline.model.TypicalSampleTrace;
import com.vpp.platform.resource.baseline.model.WindowValueTrace;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** 用户基线纯计算器，不访问数据库。 */
@Component
public class UserBaselinePointCalculator {

    private static final MathContext CALC_CONTEXT = MathContext.DECIMAL128;
    private static final BigDecimal HIGH_VALUE_FACTOR = new BigDecimal("1.5");

    /** 计算原始基线，并标识是否实际发生异常高值剔除。 */
    public RawBaselineResult calculateRaw(List<TypicalSampleTrace> inputSamples) {
        List<TypicalSampleTrace> samples = inputSamples == null
                ? List.of()
                : inputSamples.stream().filter(sample -> sample != null && sample.value() != null).toList();
        if (samples.isEmpty()) {
            return new RawBaselineResult(null, null, null, null, List.of(), List.of(),
                    UserBaselineConstants.STATUS_NO_BASELINE,
                    "该点未取得有效典型日样本，无法生成原始基线。");
        }

        BigDecimal initialMean = mean(samples.stream().map(TypicalSampleTrace::value).toList());
        BigDecimal threshold = initialMean.multiply(HIGH_VALUE_FACTOR, CALC_CONTEXT);
        List<TypicalSampleTrace> removed = samples.stream()
                .filter(sample -> sample.value().compareTo(BigDecimal.ZERO) > 0
                        && sample.value().compareTo(threshold) > 0)
                .toList();
        List<TypicalSampleTrace> retained = new ArrayList<>(samples);
        retained.removeAll(removed);
        if (retained.isEmpty()) {
            return new RawBaselineResult(initialMean, threshold, null, null, samples, removed,
                    UserBaselineConstants.STATUS_NO_BASELINE,
                    "异常高值剔除后没有剩余样本，无法生成原始基线。");
        }

        BigDecimal rawCalculation = mean(retained.stream().map(TypicalSampleTrace::value).toList());
        BigDecimal rawBaseline = truncate6(rawCalculation);
        String sampleDates = samples.stream()
                .map(sample -> sample.targetDate() + "=" + truncate6(sample.value()).toPlainString())
                .collect(Collectors.joining("，"));
        String remark;
        String status;
        if (removed.isEmpty()) {
            status = UserBaselineConstants.STATUS_NOT_REQUIRED;
            remark = "该点取得" + samples.size() + "个典型日样本（" + sampleDates + "），初始平均负荷为"
                    + truncate6(initialMean).toPlainString() + "兆瓦，异常高值阈值为"
                    + truncate6(threshold).toPlainString()
                    + "兆瓦；未发生异常高值剔除，不需要进行实时修正，最终基线等于原始基线"
                    + rawBaseline.toPlainString() + "兆瓦，K值为空。";
        } else {
            status = UserBaselineConstants.STATUS_WAITING;
            String removedText = removed.stream()
                    .map(sample -> sample.targetDate() + "=" + truncate6(sample.value()).toPlainString() + "兆瓦")
                    .collect(Collectors.joining("，"));
            remark = "该点取得" + samples.size() + "个典型日样本（" + sampleDates + "），初始平均负荷为"
                    + truncate6(initialMean).toPlainString() + "兆瓦，异常高值阈值为"
                    + truncate6(threshold).toPlainString() + "兆瓦；剔除异常高值样本：" + removedText
                    + "。使用剩余" + retained.size() + "个样本计算原始基线为"
                    + rawBaseline.toPlainString() + "兆瓦，该点需要在目标时刻计算K值并修正。";
        }
        return new RawBaselineResult(initialMean, threshold, rawCalculation, rawBaseline,
                samples, removed, status, remark);
    }

    /** 计算K和最终基线；窗口补值由调用方完成。 */
    public CorrectionResult calculateCorrection(BigDecimal rawCalculationValue,
                                                BigDecimal savedRawBaseline,
                                                List<WindowValueTrace> actualWindow,
                                                List<WindowValueTrace> baselineWindow) {
        if (actualWindow == null || actualWindow.size() != UserBaselineConstants.WINDOW_POINTS) {
            return uncorrectable(savedRawBaseline, actualWindow, baselineWindow,
                    "前4小时实际负荷窗口无法补齐，K值为空，最终基线保留原始基线"
                            + savedRawBaseline.toPlainString() + "兆瓦。");
        }
        if (baselineWindow == null || baselineWindow.size() != UserBaselineConstants.WINDOW_POINTS) {
            return uncorrectable(savedRawBaseline, actualWindow, baselineWindow,
                    "前4小时历史最终基线窗口无法补齐，K值为空，最终基线保留原始基线"
                            + savedRawBaseline.toPlainString() + "兆瓦。");
        }

        BigDecimal actualMeanRaw = mean(actualWindow.stream().map(WindowValueTrace::value).toList());
        BigDecimal baselineMeanRaw = mean(baselineWindow.stream().map(WindowValueTrace::value).toList());
        if (baselineMeanRaw.compareTo(BigDecimal.ZERO) == 0) {
            return uncorrectable(savedRawBaseline, actualWindow, baselineWindow,
                    "前4小时历史最终基线平均负荷为0，不能作为K值分母；K值为空，最终基线保留原始基线"
                            + savedRawBaseline.toPlainString() + "兆瓦。");
        }

        BigDecimal kCalculation = actualMeanRaw.divide(baselineMeanRaw, CALC_CONTEXT);
        BigDecimal finalCalculation = rawCalculationValue.multiply(kCalculation, CALC_CONTEXT);
        BigDecimal actualMean = truncate6(actualMeanRaw);
        BigDecimal baselineMean = truncate6(baselineMeanRaw);
        BigDecimal kValue = truncate6(kCalculation);
        BigDecimal finalBaseline = truncate6(finalCalculation);
        String remark = "前4小时16个实际负荷点平均值为" + actualMean.toPlainString()
                + "兆瓦，前4小时16个历史最终基线点平均值为" + baselineMean.toPlainString()
                + "兆瓦；K计算值=" + actualMeanRaw.toPlainString() + "÷"
                + baselineMeanRaw.toPlainString() + "，保存K值为" + kValue.toPlainString()
                + "；原始基线计算值为" + rawCalculationValue.toPlainString()
                + "兆瓦，修正后基线为" + finalBaseline.toPlainString() + "兆瓦。";
        return new CorrectionResult(UserBaselineConstants.STATUS_CORRECTED, actualMean,
                baselineMean, kValue, finalBaseline, actualWindow, baselineWindow, remark);
    }

    public BigDecimal truncate6(BigDecimal value) {
        return value == null ? null : value.setScale(6, RoundingMode.DOWN);
    }

    private CorrectionResult uncorrectable(BigDecimal rawBaseline,
                                           List<WindowValueTrace> actualWindow,
                                           List<WindowValueTrace> baselineWindow,
                                           String remark) {
        return new CorrectionResult(UserBaselineConstants.STATUS_UNCORRECTABLE, null, null,
                null, rawBaseline, actualWindow, baselineWindow, remark);
    }

    private BigDecimal mean(List<BigDecimal> values) {
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO,
                (left, right) -> left.add(right, CALC_CONTEXT));
        return sum.divide(BigDecimal.valueOf(values.size()), CALC_CONTEXT);
    }
}

