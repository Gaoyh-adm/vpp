package com.vpp.platform.resource.baseline.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 首次启动时按完整历史时点计算并保存的原始基线种子。
 *
 * @param pointTime 完整历史时点
 * @param dataDate 历史时点所属业务日期
 * @param slotNo 历史时点所属点序号
 * @param rawResult 该历史点完整的B0计算结果及来源
 */
public record InitialBaselineSeed(
        LocalDateTime pointTime,
        LocalDate dataDate,
        int slotNo,
        RawBaselineResult rawResult) {
}
