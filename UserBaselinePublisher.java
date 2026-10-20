package com.vpp.platform.resource.baseline;

import com.vpp.platform.resource.entity.UserBaselineDetail;
import com.vpp.platform.resource.entity.UserBaselineLoad;
import com.vpp.platform.resource.repository.UserBaselineDetailMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.List;
import java.util.Set;

/** 将MySQL中不可变的定稿结果统一发布到TDengine。 */
@Service
@RequiredArgsConstructor
public class UserBaselinePublisher {

    private static final Set<String> FINAL_STATUSES = Set.of(
            UserBaselineConstants.STATUS_NOT_REQUIRED,
            UserBaselineConstants.STATUS_NO_BASELINE,
            UserBaselineConstants.STATUS_CORRECTED,
            UserBaselineConstants.STATUS_UNCORRECTABLE);

    private final UserBaselineDetailMapper detailMapper;
    private final UserBaselineLoadStore loadStore;
    private final UserBaselineStorageSupport storageSupport;

    public int publish(List<Long> detailIds) {
        if (detailIds == null || detailIds.isEmpty()) {
            return 0;
        }
        List<UserBaselineDetail> details = detailMapper.selectBatchIds(detailIds).stream()
                .filter(detail -> isFinalStatus(detail.getCorrectionStatus()))
                .filter(detail -> UserBaselineConstants.SYNC_PENDING.equals(detail.getSyncStatus()))
                .toList();
        if (details.isEmpty()) {
            return 0;
        }
        loadStore.upsertPoints(details.stream().map(this::toLoad).toList());
        for (UserBaselineDetail sent : details) {
            if (detailMapper.confirmPublished(sent.getId(), sent.getCalcVersion(), sent.getCorrectionStatus()) == 1) {
                continue;
            }
            UserBaselineDetail current = detailMapper.selectById(sent.getId());
            boolean duplicateConfirmation = current != null
                    && sent.getCorrectionStatus().equals(current.getCorrectionStatus())
                    && UserBaselineConstants.SYNCED.equals(current.getSyncStatus());
            if (!duplicateConfirmation) {
                throw new IllegalStateException("TDengine写入成功，但MySQL按版本和定稿状态确认失败，明细ID=" + sent.getId());
            }
        }
        return details.size();
    }

    public static boolean isFinalStatus(String status) {
        return FINAL_STATUSES.contains(status);
    }

    private UserBaselineLoad toLoad(UserBaselineDetail detail) {
        return UserBaselineLoad.builder()
                .ts(Timestamp.valueOf(detail.getTargetTime()))
                .slotNo(detail.getSlotNo())
                .rawBaselineE6(storageSupport.toScaledLong(detail.getRawBaselineMw()))
                .actualWindowMeanE6(storageSupport.toScaledLong(detail.getActualWindowMeanMw()))
                .baselineWindowMeanE6(storageSupport.toScaledLong(detail.getBaselineWindowMeanMw()))
                .kE6(storageSupport.toScaledLong(detail.getKValue()))
                .finalBaselineE6(storageSupport.toScaledLong(detail.getFinalBaselineMw()))
                .correctionStatus(detail.getCorrectionStatus())
                .calculationRemark(detail.getCalculationRemark())
                .traceId(detail.getTraceCode())
                .calcVersion(detail.getCalcVersion())
                .generatedAt(Timestamp.valueOf(detail.getGeneratedAt()))
                .correctedAt(detail.getCorrectedAt() == null ? null : Timestamp.valueOf(detail.getCorrectedAt()))
                .consNo(detail.getConsNo())
                .dataDate(detail.getDataDate().toString())
                .build();
    }
}
