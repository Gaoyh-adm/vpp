package com.vpp.platform.resource.baseline;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.vpp.framework.commons.JacksonUtils;
import com.vpp.platform.resource.baseline.model.CorrectionResult;
import com.vpp.platform.resource.baseline.model.InitialBaselineSeed;
import com.vpp.platform.resource.baseline.model.UserLoadPoint;
import com.vpp.platform.resource.baseline.model.WindowValueTrace;
import com.vpp.platform.resource.entity.UserBaselineCorrectionTask;
import com.vpp.platform.resource.entity.UserBaselineDetail;
import com.vpp.platform.resource.entity.UserBaselineLoad;
import com.vpp.platform.resource.repository.UserBaselineCorrectionTaskMapper;
import com.vpp.platform.resource.repository.UserBaselineDetailMapper;
import com.vpp.platform.resource.repository.UserBaselineLoadMapper;
import com.vpp.platform.resource.repository.UserBaselineSourceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 消费时刻级任务并修正发生过典型日剔除的户号点位。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserBaselineCorrectionService {

    private final UserBaselineCorrectionTaskMapper taskMapper;
    private final UserBaselineDetailMapper detailMapper;
    private final UserBaselineSourceMapper sourceMapper;
    private final UserBaselineLoadMapper loadMapper;
    private final UserBaselineInitialWindowService initialWindowService;
    private final UserBaselinePersistenceService persistenceService;
    private final UserBaselinePublisher publisher;
    private final UserBaselineLeaseHeartbeat leaseHeartbeat;
    private final UserBaselinePointCalculator pointCalculator;
    private final UserBaselineStorageSupport storageSupport;
    private final UserBaselineProperties properties;

    /**
     * 查询并执行已经到达目标时刻的修正任务。
     *
     * @return 本次成功完成的任务数
     * @throws IllegalStateException 任一任务发生系统异常并进入等待重试时抛出
     */
    public int correctDueTasks() {
        List<UserBaselineCorrectionTask> tasks = taskMapper.selectDueRecoverable(
                Math.max(1, properties.getCorrectionTaskBatchSize()));
        int completed = 0;
        int failed = 0;
        for (UserBaselineCorrectionTask task : tasks) {
            try {
                correct(task.getId());
                UserBaselineCorrectionTask current = taskMapper.selectById(task.getId());
                if (current != null && UserBaselineConstants.TASK_SUCCESS.equals(current.getStatus())) {
                    completed++;
                }
            } catch (Exception e) {
                failed++;
                log.error("[用户基线] 到期修正任务执行失败，任务ID={}", task.getId(), e);
            }
        }
        if (failed > 0) {
            throw new IllegalStateException("本次有" + failed + "条用户基线修正任务发生系统异常，已进入等待重试状态。");
        }
        return completed;
    }

    private void correct(Long taskId) {
        String executionOwner = UUID.randomUUID().toString();
        UserBaselineCorrectionTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("用户基线修正任务不存在，任务ID=" + taskId);
        }
        if (UserBaselineConstants.TASK_SUCCESS.equals(task.getStatus())) {
            return;
        }
        int leaseSeconds = Math.max(30, properties.getCorrectionLeaseSeconds());
        if (taskMapper.claimForProcessing(taskId, executionOwner, leaseSeconds) == 0) {
            UserBaselineCorrectionTask current = taskMapper.selectById(taskId);
            if (current != null && (UserBaselineConstants.TASK_SUCCESS.equals(current.getStatus())
                    || UserBaselineConstants.TASK_PROCESSING.equals(current.getStatus()))) {
                return;
            }
            throw new IllegalStateException("用户基线修正任务当前状态不允许消费，任务ID=" + taskId);
        }
        task = taskMapper.selectById(taskId);

        try (UserBaselineLeaseHeartbeat.LeaseHandle heartbeat = leaseHeartbeat.start(
                "用户基线修正任务" + taskId, leaseSeconds,
                () -> taskMapper.renewLease(taskId, executionOwner, leaseSeconds) == 1)) {
            syncPendingDetails(task);
            long lastId = 0L;
            while (true) {
                List<UserBaselineDetail> details = detailMapper.selectList(
                        new LambdaQueryWrapper<UserBaselineDetail>()
                                .eq(UserBaselineDetail::getDataDate, task.getDataDate())
                                .eq(UserBaselineDetail::getSlotNo, task.getSlotNo())
                                .eq(UserBaselineDetail::getCalcVersion, task.getCalcVersion())
                                .eq(UserBaselineDetail::getCorrectionStatus, UserBaselineConstants.STATUS_WAITING)
                                .gt(UserBaselineDetail::getId, lastId)
                                .orderByAsc(UserBaselineDetail::getId)
                                .last("LIMIT " + Math.max(1, properties.getUserBatchSize())));
                if (details == null || details.isEmpty()) {
                    break;
                }
                heartbeat.assertActive();
                List<String> consNos = details.stream().map(UserBaselineDetail::getConsNo).distinct().toList();
                List<String> unready = detailMapper.selectUnreadyHistoryUsers(consNos, task.getCalcVersion(),
                        task.getTargetTime().minusHours(4), task.getTargetTime());
                List<UserBaselineDetail> ready = details.stream()
                        .filter(detail -> !unready.contains(detail.getConsNo())).toList();
                if (!ready.isEmpty()) {
                    List<UserBaselineDetail> calculated = calculateBatch(taskId, executionOwner, task, ready);
                    heartbeat.assertActive();
                    publisher.publishCorrections(taskId, executionOwner, calculated);
                }
                lastId = details.get(details.size() - 1).getId();
            }
            syncPendingDetails(task);
            heartbeat.assertActive();
            long waiting = countDetails(task, UserBaselineConstants.STATUS_WAITING, null);
            long pendingSync = countPendingSyncDetails(task);
            if (waiting > 0 || pendingSync > 0) {
                throw new IllegalStateException("用户基线修正任务仍有待计算" + waiting
                        + "条、待同步" + pendingSync + "条，不能标记成功。");
            }
            int success = countCompletedDetails(task, UserBaselineConstants.STATUS_CORRECTED);
            int failed = countCompletedDetails(task, UserBaselineConstants.STATUS_UNCORRECTABLE);
            int expected = task.getTotalCount() == null ? 0 : task.getTotalCount();
            if (success + failed != expected) {
                throw new IllegalStateException("用户基线修正任务要求同步" + expected + "条，实际已确认同步"
                        + (success + failed) + "条，不能标记成功。");
            }
            if (taskMapper.complete(taskId, executionOwner, success, failed) != 1) {
                throw new IllegalStateException("用户基线修正任务完成时执行权已经失效，任务ID=" + taskId);
            }
        } catch (Exception e) {
            taskMapper.releaseForRetry(taskId, executionOwner,
                    "用户基线修正发生系统异常：" + e.getMessage());
            throw e;
        }
    }

    private List<UserBaselineDetail> calculateBatch(Long taskId, String executionOwner,
                                                     UserBaselineCorrectionTask task,
                                                     List<UserBaselineDetail> details) {
        List<String> consNos = details.stream().map(UserBaselineDetail::getConsNo).distinct().toList();
        LocalDateTime targetTime = task.getTargetTime();
        LocalDateTime queryStart = targetTime.minusHours(8);
        List<UserLoadPoint> actualRows = sourceMapper.selectByConsNosAndTimeRange(consNos,
                Timestamp.valueOf(queryStart), Timestamp.valueOf(targetTime));
        List<UserBaselineLoad> baselineRows = loadMapper.selectByConsNosAndTimeRange(consNos,
                Timestamp.valueOf(queryStart), Timestamp.valueOf(targetTime));
        Map<String, Map<LocalDateTime, UserLoadPoint>> actualByUser = actualMap(actualRows);
        Map<String, Map<LocalDateTime, UserBaselineLoad>> baselineByUser = baselineMap(baselineRows);
        Map<String, Map<LocalDateTime, InitialBaselineSeed>> seeds =
                initialWindowService.findByUsers(consNos, task.getCalcVersion());
        List<UserBaselineDetail> needInitialization = new ArrayList<>();
        for (UserBaselineDetail detail : details) {
            if (seeds.containsKey(detail.getConsNo())) {
                continue;
            }
            WindowBuild probe = buildBaselineWindow(targetTime,
                    baselineByUser.getOrDefault(detail.getConsNo(), Map.of()), Map.of());
            if (probe.missingTime() != null) {
                needInitialization.add(detail);
            }
        }
        if (!needInitialization.isEmpty()) {
            persistenceService.saveInitialWindows(taskId, executionOwner,
                    initialWindowService.buildMissingWindows(
                            needInitialization, targetTime, task.getCalcVersion()));
            seeds = initialWindowService.findByUsers(consNos, task.getCalcVersion());
        }

        for (UserBaselineDetail detail : details) {
            WindowBuild actualWindow = buildActualWindow(targetTime,
                    actualByUser.getOrDefault(detail.getConsNo(), Map.of()));
            WindowBuild baselineWindow = buildBaselineWindow(targetTime,
                    baselineByUser.getOrDefault(detail.getConsNo(), Map.of()),
                    seeds.getOrDefault(detail.getConsNo(), Map.of()));

            CorrectionResult result;
            List<String> missingReasons = new ArrayList<>(2);
            if (actualWindow.missingTime() != null) {
                missingReasons.add("前4小时实际负荷窗口中的" + actualWindow.missingTime()
                        + "缺失，已在该点前后各4小时内按照距离由近到远、同距离前值优先的规则查找，仍未找到可用值");
            }
            if (baselineWindow.missingTime() != null) {
                missingReasons.add("前4小时历史最终基线窗口中的" + baselineWindow.missingTime()
                        + "无法取得已输出结果、最近有效前值或初始化原始基线");
            }
            if (!missingReasons.isEmpty()) {
                String remark = String.join("；", missingReasons) + "；K值为空，最终基线保留原始基线"
                        + detail.getRawBaselineMw().toPlainString() + "兆瓦。";
                result = uncorrectable(detail, actualWindow.values(), baselineWindow.values(), remark);
            } else {
                result = pointCalculator.calculateCorrection(detail.getRawBaselineCalcMw(),
                        detail.getRawBaselineMw(), actualWindow.values(), baselineWindow.values());
            }

            LocalDateTime correctedAt = LocalDateTime.now();
            detail.setActualWindowJson(JacksonUtils.toJson(result.actualWindow()));
            detail.setBaselineWindowJson(JacksonUtils.toJson(result.baselineWindow()));
            detail.setActualWindowMeanMw(result.actualMean());
            detail.setBaselineWindowMeanMw(result.baselineMean());
            detail.setKValue(result.kValue());
            detail.setFinalBaselineMw(result.finalBaseline());
            detail.setCorrectionStatus(result.status());
            detail.setCalculationRemark(detail.getCalculationRemark() + result.chineseRemark());
            detail.setCorrectedAt(correctedAt);
            detail.setSyncStatus(UserBaselineConstants.SYNC_PENDING);
            detail.setSyncedAt(null);
        }
        return details;
    }

    private int countCompletedDetails(UserBaselineCorrectionTask task, String correctionStatus) {
        return Math.toIntExact(countDetails(task, correctionStatus, UserBaselineConstants.SYNCED));
    }

    private long countDetails(UserBaselineCorrectionTask task, String correctionStatus, String syncStatus) {
        LambdaQueryWrapper<UserBaselineDetail> wrapper = new LambdaQueryWrapper<UserBaselineDetail>()
                .eq(UserBaselineDetail::getDataDate, task.getDataDate())
                .eq(UserBaselineDetail::getSlotNo, task.getSlotNo())
                .eq(UserBaselineDetail::getCalcVersion, task.getCalcVersion())
                .eq(UserBaselineDetail::getCorrectionStatus, correctionStatus);
        if (syncStatus != null) {
            wrapper.eq(UserBaselineDetail::getSyncStatus, syncStatus);
        }
        Long count = detailMapper.selectCount(wrapper);
        return count == null ? 0L : count;
    }

    private long countPendingSyncDetails(UserBaselineCorrectionTask task) {
        Long count = detailMapper.selectCount(new LambdaQueryWrapper<UserBaselineDetail>()
                .eq(UserBaselineDetail::getDataDate, task.getDataDate())
                .eq(UserBaselineDetail::getSlotNo, task.getSlotNo())
                .eq(UserBaselineDetail::getCalcVersion, task.getCalcVersion())
                .in(UserBaselineDetail::getCorrectionStatus,
                        UserBaselineConstants.STATUS_CORRECTED, UserBaselineConstants.STATUS_UNCORRECTABLE)
                .eq(UserBaselineDetail::getSyncStatus, UserBaselineConstants.SYNC_PENDING));
        return count == null ? 0L : count;
    }

    private WindowBuild buildActualWindow(LocalDateTime targetTime,
                                          Map<LocalDateTime, UserLoadPoint> source) {
        List<WindowValueTrace> result = new ArrayList<>(UserBaselineConstants.WINDOW_POINTS);
        for (int i = UserBaselineConstants.WINDOW_POINTS; i >= 1; i--) {
            LocalDateTime pointTime = targetTime.minusMinutes(i * 15L);
            UserLoadPoint direct = source.get(pointTime);
            if (direct != null && direct.getLoadMw() != null) {
                result.add(new WindowValueTrace(pointTime, pointTime, direct.getLoadMw(),
                        "采用目标时点真实负荷，来源类型：" + chineseSource(direct.getSrcType()), "当前点", 0));
                continue;
            }
            WindowValueTrace fitted = null;
            for (int distance = 15; distance <= 240 && fitted == null; distance += 15) {
                LocalDateTime previous = pointTime.minusMinutes(distance);
                UserLoadPoint previousValue = source.get(previous);
                if (previousValue != null && previousValue.getLoadMw() != null) {
                    fitted = new WindowValueTrace(pointTime, previous, previousValue.getLoadMw(),
                            "目标时点缺失，采用前方已有真实负荷，来源类型："
                                    + chineseSource(previousValue.getSrcType()), "前值", distance);
                    break;
                }
                LocalDateTime next = pointTime.plusMinutes(distance);
                if (!next.isAfter(targetTime)) {
                    UserLoadPoint nextValue = source.get(next);
                    if (nextValue != null && nextValue.getLoadMw() != null) {
                        fitted = new WindowValueTrace(pointTime, next, nextValue.getLoadMw(),
                                "目标时点缺失，采用后方已有真实负荷，来源类型："
                                        + chineseSource(nextValue.getSrcType()), "后值", distance);
                    }
                }
            }
            if (fitted == null) {
                return new WindowBuild(result, pointTime);
            }
            result.add(fitted);
        }
        return new WindowBuild(result, null);
    }

    private WindowBuild buildBaselineWindow(LocalDateTime targetTime,
                                             Map<LocalDateTime, UserBaselineLoad> source,
                                             Map<LocalDateTime, InitialBaselineSeed> seeds) {
        List<WindowValueTrace> result = new ArrayList<>(UserBaselineConstants.WINDOW_POINTS);
        for (int i = UserBaselineConstants.WINDOW_POINTS; i >= 1; i--) {
            LocalDateTime pointTime = targetTime.minusMinutes(i * 15L);
            UserBaselineLoad direct = source.get(pointTime);
            if (direct != null && UserBaselineConstants.STATUS_WAITING.equals(direct.getCorrectionStatus())) {
                throw new IllegalStateException("户号" + direct.getConsNo() + "的历史点" + pointTime
                        + "仍等待修正，当前点必须在前一点完成后再计算。");
            }
            if (direct != null && direct.getFinalBaselineE6() != null) {
                result.add(new WindowValueTrace(pointTime, pointTime,
                        storageSupport.fromScaledLong(direct.getFinalBaselineE6()),
                        "采用该历史时点已输出的最终基线", "当前点", 0));
                continue;
            }
            Map.Entry<LocalDateTime, UserBaselineLoad> previousPublished = source.entrySet().stream()
                    .filter(entry -> entry.getKey().isBefore(pointTime))
                    .filter(entry -> entry.getValue().getFinalBaselineE6() != null)
                    .filter(entry -> !UserBaselineConstants.STATUS_WAITING.equals(
                            entry.getValue().getCorrectionStatus()))
                    .max(Map.Entry.comparingByKey())
                    .orElse(null);
            InitialBaselineSeed seed = seeds.get(pointTime);
            boolean exactSeedUsable = seed != null && seed.rawResult() != null
                    && seed.rawResult().rawBaseline() != null;
            if (exactSeedUsable && previousPublished != null) {
                UserBaselineLoad previous = previousPublished.getValue();
                LocalDateTime previousTime = previousPublished.getKey();
                int distance = Math.toIntExact(java.time.Duration.between(previousTime, pointTime).toMinutes());
                result.add(new WindowValueTrace(pointTime, previousTime,
                        storageSupport.fromScaledLong(previous.getFinalBaselineE6()),
                        "历史最终基线缺失，采用最近有效前值", "前值", distance));
                continue;
            }
            if (exactSeedUsable) {
                result.add(new WindowValueTrace(pointTime, seed.pointTime(), seed.rawResult().rawBaseline(),
                        "首次启动缺少历史最终基线，采用历史业务日期" + seed.dataDate() + "第"
                                + seed.slotNo() + "点单独计算并保存的B0；" + seed.rawResult().chineseRemark(),
                        "初始化种子", 0));
                continue;
            }
            Map.Entry<LocalDateTime, InitialBaselineSeed> previousSeed = seeds.entrySet().stream()
                    .filter(entry -> entry.getKey().isBefore(pointTime))
                    .filter(entry -> entry.getValue() != null && entry.getValue().rawResult() != null)
                    .filter(entry -> entry.getValue().rawResult().rawBaseline() != null)
                    .max(Map.Entry.comparingByKey())
                    .orElse(null);
            boolean useInitializationPrevious = previousSeed != null && (previousPublished == null
                    || previousSeed.getKey().isAfter(previousPublished.getKey()));
            if (useInitializationPrevious) {
                InitialBaselineSeed previousSeedValue = previousSeed.getValue();
                int distance = Math.toIntExact(java.time.Duration.between(
                        previousSeed.getKey(), pointTime).toMinutes());
                result.add(new WindowValueTrace(pointTime, previousSeed.getKey(),
                        previousSeedValue.rawResult().rawBaseline(),
                        "当前初始化点缺失或无有效B0，采用最近有效初始化前值；该补值仅用于本次计算，不修改初始化记录和已发布结果",
                        "初始化前值", distance));
                continue;
            }
            if (previousPublished != null) {
                UserBaselineLoad previous = previousPublished.getValue();
                LocalDateTime previousTime = previousPublished.getKey();
                int distance = Math.toIntExact(java.time.Duration.between(previousTime, pointTime).toMinutes());
                result.add(new WindowValueTrace(pointTime, previousTime,
                        storageSupport.fromScaledLong(previous.getFinalBaselineE6()),
                        "历史最终基线和当前初始化点缺失，采用最近有效已发布前值", "前值", distance));
                continue;
            }
            if (seed != null && seed.rawResult() != null) {
                result.add(new WindowValueTrace(pointTime, seed.pointTime(), null,
                        "首次初始化已按历史业务日期" + seed.dataDate() + "第" + seed.slotNo()
                                + "点计算B0，但未取得有效结果；" + seed.rawResult().chineseRemark(),
                        "初始化失败", 0));
            }
            return new WindowBuild(result, pointTime);
        }
        return new WindowBuild(result, null);
    }

    private Map<String, Map<LocalDateTime, UserLoadPoint>> actualMap(List<UserLoadPoint> rows) {
        Map<String, Map<LocalDateTime, UserLoadPoint>> result = new HashMap<>();
        if (rows != null) {
            rows.stream().filter(row -> row != null && row.getConsNo() != null && row.getTs() != null)
                    .forEach(row -> result.computeIfAbsent(row.getConsNo(), ignored -> new HashMap<>())
                            .put(row.getTs().toLocalDateTime(), row));
        }
        return result;
    }

    private Map<String, Map<LocalDateTime, UserBaselineLoad>> baselineMap(List<UserBaselineLoad> rows) {
        Map<String, Map<LocalDateTime, UserBaselineLoad>> result = new HashMap<>();
        if (rows != null) {
            rows.stream().filter(row -> row != null && row.getConsNo() != null && row.getTs() != null)
                    .forEach(row -> result.computeIfAbsent(row.getConsNo(), ignored -> new HashMap<>())
                            .put(row.getTs().toLocalDateTime(), row));
        }
        return result;
    }

    // 优先补写已经计算完成但尚未同步到TDengine的原结果，不重新计算K。
    private void syncPendingDetails(UserBaselineCorrectionTask task) {
        long lastId = 0L;
        while (true) {
            List<UserBaselineDetail> pending = detailMapper.selectList(
                    new LambdaQueryWrapper<UserBaselineDetail>()
                            .eq(UserBaselineDetail::getDataDate, task.getDataDate())
                            .eq(UserBaselineDetail::getSlotNo, task.getSlotNo())
                            .eq(UserBaselineDetail::getCalcVersion, task.getCalcVersion())
                            .in(UserBaselineDetail::getCorrectionStatus,
                                    UserBaselineConstants.STATUS_WAITING,
                                    UserBaselineConstants.STATUS_CORRECTED,
                                    UserBaselineConstants.STATUS_UNCORRECTABLE)
                            .eq(UserBaselineDetail::getSyncStatus, UserBaselineConstants.SYNC_PENDING)
                            .gt(UserBaselineDetail::getId, lastId)
                            .orderByAsc(UserBaselineDetail::getId)
                            .last("LIMIT " + Math.max(1, properties.getUserBatchSize())));
            if (pending == null || pending.isEmpty()) {
                return;
            }
            publisher.publish(pending.stream().map(UserBaselineDetail::getId).toList());
            lastId = pending.get(pending.size() - 1).getId();
        }
    }

    private CorrectionResult uncorrectable(UserBaselineDetail detail,
                                           List<WindowValueTrace> actual,
                                           List<WindowValueTrace> baseline,
                                           String remark) {
        return new CorrectionResult(UserBaselineConstants.STATUS_UNCORRECTABLE, null, null,
                null, detail.getRawBaselineMw(), actual, baseline, remark);
    }

    private String chineseSource(String sourceType) {
        if (sourceType == null || sourceType.isBlank()) {
            return "负荷来源类型未标注";
        }
        return switch (sourceType.trim().toUpperCase()) {
            case "DEVICE" -> "设备负荷汇总";
            case "STATION" -> "场站负荷汇总";
            case "STAKE" -> "充电桩负荷汇总";
            case "MANUAL" -> "人工维护负荷";
            default -> "负荷来源代码“" + sourceType + "”";
        };
    }

    private record WindowBuild(List<WindowValueTrace> values, LocalDateTime missingTime) {
    }

}
