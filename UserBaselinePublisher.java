package com.vpp.platform.resource.baseline;

import com.vpp.platform.resource.entity.UserBaselineDetail;
import com.vpp.platform.resource.entity.UserBaselineLoad;
import com.vpp.platform.resource.repository.UserBaselineDetailMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/** 单实例内统一发布原始基线及修正结果，协调两阶段写入顺序。 */
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
    private final UserBaselinePersistenceService persistenceService;

    // Spring单例中的同一把锁；只协调保存和发布，选样及K计算在锁外。
    private final ReentrantLock publicationLock = new ReentrantLock();

    public int publish(List<Long> detailIds) {
        publicationLock.lock();
        try {
            return publishLocked(detailIds);
        } finally {
            publicationLock.unlock();
        }
    }

    /** 必须先确认B0已发布，再提交修正结果并发布；调用方不得套外层事务。 */
    public int publishCorrections(Long taskId, String owner,
                                  List<UserBaselineDetail> calculated) {
        if (calculated == null || calculated.isEmpty()) {
            return 0;
        }
        publicationLock.lock();
        try {
            List<Long> ids = calculated.stream().map(UserBaselineDetail::getId).toList();
            // 从MySQL重新读；初次发布失败则先补写B0，失败时不提交修正。
            publishLocked(ids);
            // 独立Bean中的MySQL事务在返回前提交，接着才发布修正值。
            List<UserBaselineDetail> saved = persistenceService.saveCorrections(
                    taskId, owner, calculated);
            return publishLocked(saved.stream().map(UserBaselineDetail::getId).toList());
        } finally {
            publicationLock.unlock();
        }
    }

    // 所有读取、TDengine写入及同步确认都在publicationLock内完成。
    private int publishLocked(List<Long> detailIds) {
        if (detailIds == null || detailIds.isEmpty()) {
            return 0;
        }
        List<UserBaselineDetail> details = detailMapper.selectBatchIds(detailIds).stream()
                .filter(detail -> UserBaselineConstants.STATUS_WAITING.equals(detail.getCorrectionStatus())
                        || isFinalStatus(detail.getCorrectionStatus()))
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
                    && sent.getCalcVersion().equals(current.getCalcVersion())
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
