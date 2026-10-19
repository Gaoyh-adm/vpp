package com.vpp.platform.resource.baseline;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.vpp.framework.commons.JacksonUtils;
import com.vpp.platform.resource.baseline.model.CorrectionResult;
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

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 消费时刻级任务并修正发生过典型日剔除的户号点位。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserBaselineCorrectionService {

    private final UserBaselineCorrectionTaskMapper taskMapper;
    private final UserBaselineDetailMapper detailMapper;
    private final UserBaselineSourceMapper sourceMapper;
    private final UserBaselineLoadMapper loadMapper;
    private final UserBaselineLoadStore loadStore;
    private final UserBaselinePointCalculator pointCalculator;
    private final UserBaselineStorageSupport storageSupport;
    private final UserBaselineProperties properties;

    /** 查询并执行已经到达目标时刻的修正任务。 */
    public int correctDueTasks() {
        List<UserBaselineCorrectionTask> tasks = taskMapper.selectList(
                new LambdaQueryWrapper<UserBaselineCorrectionTask>()
                        .in(UserBaselineCorrectionTask::getStatus,
                                UserBaselineConstants.TASK_PENDING, UserBaselineConstants.TASK_RETRY_WAIT)
                        .le(UserBaselineCorrectionTask::getScheduledTime, LocalDateTime.now())
                        .orderByAsc(UserBaselineCorrectionTask::getScheduledTime)
                        .last("LIMIT " + Math.max(1, properties.getCorrectionTaskBatchSize())));
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
        UserBaselineCorrectionTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("用户基线修正任务不存在，任务ID=" + taskId);
        }
        if (UserBaselineConstants.TASK_SUCCESS.equals(task.getStatus())) {
            return;
        }
        if (taskMapper.claimForProcessing(taskId) == 0) {
            UserBaselineCorrectionTask current = taskMapper.selectById(taskId);
            if (current != null && (UserBaselineConstants.TASK_SUCCESS.equals(current.getStatus())
                    || UserBaselineConstants.TASK_PROCESSING.equals(current.getStatus()))) {
                return;
            }
            throw new IllegalStateException("用户基线修正任务当前状态不允许消费，任务ID=" + taskId);
        }

        int success = countCompletedDetails(task, UserBaselineConstants.STATUS_CORRECTED);
        int failed = countCompletedDetails(task, UserBaselineConstants.STATUS_UNCORRECTABLE);
        long lastId = 0L;
        try {
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
                BatchCount count = correctBatch(task, details);
                success += count.success();
                failed += count.failed();
                lastId = details.get(details.size() - 1).getId();
            }
            task.setStatus(UserBaselineConstants.TASK_SUCCESS);
            task.setSuccessCount(success);
            task.setFailedCount(failed);
            task.setCompletedAt(LocalDateTime.now());
            task.setLastError(null);
            taskMapper.updateById(task);
        } catch (Exception e) {
            task.setStatus(UserBaselineConstants.TASK_RETRY_WAIT);
            task.setRetryCount(task.getRetryCount() == null ? 1 : task.getRetryCount() + 1);
            task.setLastError("用户基线修正发生系统异常：" + e.getMessage());
            taskMapper.updateById(task);
            throw e;
        }
    }

    private BatchCount correctBatch(UserBaselineCorrectionTask task, List<UserBaselineDetail> details) {
        List<String> consNos = details.stream().map(UserBaselineDetail::getConsNo).distinct().toList();
        LocalDateTime targetTime = task.getTargetTime();
        LocalDateTime queryStart = targetTime.minusHours(8);
        List<UserLoadPoint> actualRows = sourceMapper.selectByConsNosAndTimeRange(consNos,
                Timestamp.valueOf(queryStart), Timestamp.valueOf(targetTime));
        List<UserBaselineLoad> baselineRows = loadMapper.selectByConsNosAndTimeRange(consNos,
                Timestamp.valueOf(queryStart), Timestamp.valueOf(targetTime));
        Map<String, Map<LocalDateTime, UserLoadPoint>> actualByUser = actualMap(actualRows);
        Map<String, Map<LocalDateTime, UserBaselineLoad>> baselineByUser = baselineMap(baselineRows);
        Map<String, Map<Integer, UserBaselineDetail>> seeds = seedMap(details.get(0).getDataDate(), consNos);

        int success = 0;
        int failed = 0;
        List<UserBaselineLoad> updates = new ArrayList<>();
        for (UserBaselineDetail detail : details) {
            WindowBuild actualWindow = buildActualWindow(targetTime,
                    actualByUser.getOrDefault(detail.getConsNo(), Map.of()));
            WindowBuild baselineWindow = buildBaselineWindow(targetTime,
                    baselineByUser.getOrDefault(detail.getConsNo(), Map.of()),
                    seeds.getOrDefault(detail.getConsNo(), Map.of()));

            CorrectionResult result;
            if (actualWindow.missingTime() != null) {
                String remark = "前4小时实际负荷窗口中的" + actualWindow.missingTime()
                        + "缺失，已在该点前后各4小时内按照距离由近到远、同距离前值优先的规则查找，仍未找到可用值；"
                        + "K值为空，最终基线保留原始基线" + detail.getRawBaselineMw().toPlainString() + "兆瓦。";
                result = uncorrectable(detail, actualWindow.values(), baselineWindow.values(), remark);
            } else if (baselineWindow.missingTime() != null) {
                String remark = "前4小时历史最终基线窗口中的" + baselineWindow.missingTime()
                        + "无法取得已输出结果、最近前值或初始化原始基线；K值为空，最终基线保留原始基线"
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
            detailMapper.updateById(detail);
            updates.add(toUpdatedLoad(detail));
            if (UserBaselineConstants.STATUS_CORRECTED.equals(result.status())) {
                success++;
            } else {
                failed++;
            }
        }
        loadStore.upsertPoints(updates);
        return new BatchCount(success, failed);
    }

    private int countCompletedDetails(UserBaselineCorrectionTask task, String correctionStatus) {
        Long count = detailMapper.selectCount(new LambdaQueryWrapper<UserBaselineDetail>()
                .eq(UserBaselineDetail::getDataDate, task.getDataDate())
                .eq(UserBaselineDetail::getSlotNo, task.getSlotNo())
                .eq(UserBaselineDetail::getCalcVersion, task.getCalcVersion())
                .eq(UserBaselineDetail::getCorrectionStatus, correctionStatus));
        return count == null ? 0 : Math.toIntExact(count);
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
                                            Map<Integer, UserBaselineDetail> seeds) {
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
            UserBaselineLoad previous = source.entrySet().stream()
                    .filter(entry -> entry.getKey().isBefore(pointTime))
                    .filter(entry -> entry.getValue().getFinalBaselineE6() != null)
                    .filter(entry -> !UserBaselineConstants.STATUS_WAITING.equals(
                            entry.getValue().getCorrectionStatus()))
                    .max(Map.Entry.comparingByKey())
                    .map(Map.Entry::getValue)
                    .orElse(null);
            if (previous != null) {
                LocalDateTime previousTime = previous.getTs().toLocalDateTime();
                int distance = Math.toIntExact(java.time.Duration.between(previousTime, pointTime).toMinutes());
                result.add(new WindowValueTrace(pointTime, previousTime,
                        storageSupport.fromScaledLong(previous.getFinalBaselineE6()),
                        "历史最终基线缺失，采用最近有效前值", "前值", distance));
                continue;
            }
            int slot = slotForTime(pointTime);
            UserBaselineDetail seed = seeds.get(slot);
            if (seed == null || seed.getRawBaselineMw() == null) {
                return new WindowBuild(result, pointTime);
            }
            result.add(new WindowValueTrace(pointTime, seed.getTargetTime(), seed.getRawBaselineMw(),
                    "首次启动缺少历史最终基线，采用当日同点原始基线作为初始化种子", "初始化种子", 0));
        }
        return new WindowBuild(result, null);
    }

    private Map<String, Map<Integer, UserBaselineDetail>> seedMap(java.time.LocalDate dataDate,
                                                                 List<String> consNos) {
        List<UserBaselineDetail> rows = detailMapper.selectList(new LambdaQueryWrapper<UserBaselineDetail>()
                .eq(UserBaselineDetail::getDataDate, dataDate)
                .eq(UserBaselineDetail::getCalcVersion, properties.getCalcVersion())
                .in(UserBaselineDetail::getConsNo, consNos));
        Map<String, Map<Integer, UserBaselineDetail>> result = new HashMap<>();
        for (UserBaselineDetail row : rows) {
            result.computeIfAbsent(row.getConsNo(), ignored -> new HashMap<>()).put(row.getSlotNo(), row);
        }
        return result;
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

    private UserBaselineLoad toUpdatedLoad(UserBaselineDetail detail) {
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

    private CorrectionResult uncorrectable(UserBaselineDetail detail,
                                           List<WindowValueTrace> actual,
                                           List<WindowValueTrace> baseline,
                                           String remark) {
        return new CorrectionResult(UserBaselineConstants.STATUS_UNCORRECTABLE, null, null,
                null, detail.getRawBaselineMw(), actual, baseline, remark);
    }

    private int slotForTime(LocalDateTime time) {
        int minute = time.getHour() * 60 + time.getMinute();
        return minute == 0 ? UserBaselineConstants.POINTS_PER_DAY : minute / 15;
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

    private record BatchCount(int success, int failed) {
    }
}
