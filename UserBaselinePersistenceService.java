package com.vpp.platform.resource.baseline;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.vpp.platform.resource.entity.UserBaselineDetail;
import com.vpp.platform.resource.entity.UserBaselineInitialWindow;
import com.vpp.platform.resource.repository.UserBaselineCorrectionTaskMapper;
import com.vpp.platform.resource.repository.UserBaselineDetailMapper;
import com.vpp.platform.resource.repository.UserBaselineInitialWindowMapper;
import com.vpp.platform.resource.repository.UserBaselineRunMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 在独立MySQL短事务中校验执行权并保存计算结果。 */
@Service
@RequiredArgsConstructor
public class UserBaselinePersistenceService {

    private final UserBaselineRunMapper runMapper;
    private final UserBaselineCorrectionTaskMapper taskMapper;
    private final UserBaselineDetailMapper detailMapper;
    private final UserBaselineInitialWindowMapper initialWindowMapper;
    private final UserBaselineProperties properties;

    @Transactional(rollbackFor = Exception.class)
    public void saveGeneratedDetails(Long runId, String owner, List<UserBaselineDetail> details) {
        if (runMapper.selectOwnedActiveForUpdate(runId, owner) == null) {
            throw new IllegalStateException("每日生成任务已经失去执行权，禁止保存明细，运行ID=" + runId);
        }
        if (details == null || details.isEmpty()) {
            return;
        }
        List<String> consNos = details.stream().map(UserBaselineDetail::getConsNo).distinct().toList();
        UserBaselineDetail first = details.getFirst();
        List<UserBaselineDetail> existing = detailMapper.selectList(new LambdaQueryWrapper<UserBaselineDetail>()
                .eq(UserBaselineDetail::getDataDate, first.getDataDate())
                .eq(UserBaselineDetail::getCalcVersion, first.getCalcVersion())
                .in(UserBaselineDetail::getConsNo, consNos));
        Set<String> keys = new HashSet<>();
        existing.forEach(row -> keys.add(row.getConsNo() + "#" + row.getSlotNo()));
        List<UserBaselineDetail> missing = details.stream()
                .filter(row -> !keys.contains(row.getConsNo() + "#" + row.getSlotNo())).toList();
        int batchSize = Math.max(1, properties.getDetailWriteBatchSize());
        for (int start = 0; start < missing.size(); start += batchSize) {
            detailMapper.insert(missing.subList(start, Math.min(start + batchSize, missing.size())));
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void saveInitialWindows(Long taskId, String owner, List<UserBaselineInitialWindow> rows) {
        requireTask(taskId, owner);
        for (UserBaselineInitialWindow row : rows) {
            initialWindowMapper.insertIfAbsent(row);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public List<UserBaselineDetail> saveCorrections(Long taskId, String owner,
                                                    List<UserBaselineDetail> calculated) {
        requireTask(taskId, owner);
        for (UserBaselineDetail detail : calculated) {
            UserBaselineDetail current = detailMapper.selectForUpdate(detail.getId());
            if (current == null) {
                throw new IllegalStateException("用户基线明细不存在，明细ID=" + detail.getId());
            }
            if (UserBaselineConstants.STATUS_WAITING.equals(current.getCorrectionStatus())) {
                if (detailMapper.updateWaitingCorrection(detail) != 1) {
                    throw new IllegalStateException("用户基线修正结果条件保存失败，明细ID=" + detail.getId());
                }
            } else if (!UserBaselinePublisher.isFinalStatus(current.getCorrectionStatus())) {
                throw new IllegalStateException("用户基线明细状态异常，禁止覆盖，明细ID=" + detail.getId());
            }
        }
        return detailMapper.selectBatchIds(calculated.stream().map(UserBaselineDetail::getId).toList());
    }

    private void requireTask(Long taskId, String owner) {
        if (taskMapper.selectOwnedActiveForUpdate(taskId, owner) == null) {
            throw new IllegalStateException("修正任务已经失去执行权，禁止保存结果，任务ID=" + taskId);
        }
    }
}
