package com.vpp.platform.resource.baseline.model;

/** 用户基线生成任务统计。 */
public record UserBaselineJobResult(int total, int success, int failed, int correctionPoints) {
}

