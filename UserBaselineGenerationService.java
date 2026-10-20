package com.vpp.platform.resource.baseline;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.vpp.framework.commons.JacksonUtils;
import com.vpp.platform.resource.baseline.model.RawBaselineResult;
import com.vpp.platform.resource.baseline.model.TypicalSampleTrace;
import com.vpp.platform.resource.baseline.model.UserBaselineJobResult;
import com.vpp.platform.resource.baseline.model.UserBaselineSlotSummary;
import com.vpp.platform.resource.baseline.model.UserLoadPoint;
import com.vpp.platform.resource.entity.AdjustmentDay;
import com.vpp.platform.resource.entity.AggregateUser;
import com.vpp.platform.resource.entity.BaselineCalendarDay;
import com.vpp.platform.resource.entity.UserBaselineCorrectionTask;
import com.vpp.platform.resource.entity.UserBaselineDetail;
import com.vpp.platform.resource.entity.UserBaselineRun;
import com.vpp.platform.resource.repository.AdjustmentDayMapper;
import com.vpp.platform.resource.repository.AggregateUserMapper;
import com.vpp.platform.resource.repository.BaselineCalendarDayMapper;
import com.vpp.platform.resource.repository.UserBaselineCorrectionTaskMapper;
import com.vpp.platform.resource.repository.UserBaselineDetailMapper;
import com.vpp.platform.resource.repository.UserBaselineRunMapper;
import com.vpp.platform.resource.repository.UserBaselineSourceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.UUID;

/** 按户号分批生成当日96点原始基线。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserBaselineGenerationService {

    private final AggregateUserMapper aggregateUserMapper;
    private final AdjustmentDayMapper adjustmentDayMapper;
    private final BaselineCalendarDayMapper calendarDayMapper;
    private final UserBaselineSourceMapper sourceMapper;
    private final UserBaselineRunMapper runMapper;
    private final UserBaselineDetailMapper detailMapper;
    private final UserBaselineCorrectionTaskMapper taskMapper;
    private final UserBaselinePersistenceService persistenceService;
    private final UserBaselinePublisher publisher;
    private final UserBaselineLeaseHeartbeat leaseHeartbeat;
    private final TypicalSampleSelector sampleSelector;
    private final UserBaselinePointCalculator pointCalculator;
    private final UserBaselineStorageSupport storageSupport;
    private final UserBaselineProperties properties;

    public UserBaselineJobResult generate(LocalDate dataDate) {
        String calcVersion = properties.getCalcVersion();
        long totalLong = aggregateUserMapper.selectCount(activeUserWrapper());
        int total = Math.toIntExact(totalLong);
        LocalDateTime snapshotTime = LocalDateTime.now();
        String executionOwner = UUID.randomUUID().toString();
        int leaseSeconds = Math.max(30, properties.getCorrectionLeaseSeconds());
        UserBaselineRun run = acquireRun(dataDate, calcVersion, total, snapshotTime,
                executionOwner, leaseSeconds);

        int success = 0;
        int failed = 0;
        int correctionPoints = 0;
        long lastId = 0L;
        int batchSize = Math.max(1, properties.getUserBatchSize());
        try (UserBaselineLeaseHeartbeat.LeaseHandle heartbeat = leaseHeartbeat.start(
                "用户基线每日生成" + run.getId(), leaseSeconds,
                () -> runMapper.renewLease(run.getId(), executionOwner, leaseSeconds) == 1)) {
            BaselineCalendarDay targetDay = calendarDayMapper.selectOne(
                    new LambdaQueryWrapper<BaselineCalendarDay>()
                            .eq(BaselineCalendarDay::getCalendarDate, dataDate).last("LIMIT 1"));
            if (targetDay == null) {
                throw new IllegalStateException("业务日期" + dataDate + "尚未维护日历，不能生成用户基线。");
            }
            List<BaselineCalendarDay> historyDays = calendarDayMapper.selectList(
                    new LambdaQueryWrapper<BaselineCalendarDay>()
                            .lt(BaselineCalendarDay::getCalendarDate, dataDate)
                            .orderByDesc(BaselineCalendarDay::getCalendarDate));
            List<BaselineCalendarDay> sourceDays = sourceDays(targetDay, historyDays);
            if (sourceDays.isEmpty()) {
                throw new IllegalStateException("业务日期" + dataDate + "没有可用的候选日历范围。");
            }
            while (true) {
                heartbeat.assertActive();
                LambdaQueryWrapper<AggregateUser> wrapper = activeUserWrapper()
                        .gt(AggregateUser::getId, lastId)
                        .orderByAsc(AggregateUser::getId)
                        .last("LIMIT " + batchSize);
                List<AggregateUser> users = aggregateUserMapper.selectList(wrapper);
                if (users == null || users.isEmpty()) {
                    break;
                }
                BatchResult batchResult = processBatch(run.getId(), executionOwner, dataDate, targetDay,
                        historyDays, sourceDays, users, snapshotTime);
                success += batchResult.success();
                failed += batchResult.failed();
                lastId = users.get(users.size() - 1).getId();
            }
            correctionPoints = reconcileCorrectionTasks(dataDate, calcVersion);
            heartbeat.assertActive();
            String finalStatus = failed == 0 ? "SUCCESS" : "PARTIAL_FAILED";
            if (runMapper.complete(run.getId(), executionOwner, finalStatus, success, failed) != 1) {
                throw new IllegalStateException("用户基线每日生成完成时执行权已经失效，运行ID=" + run.getId());
            }
        } catch (Exception e) {
            runMapper.releaseForFailure(run.getId(), executionOwner, success,
                    Math.max(failed, total - success), "用户基线生成失败：" + e.getMessage());
            throw e;
        }
        return new UserBaselineJobResult(total, success, failed, correctionPoints);
    }

    private UserBaselineRun acquireRun(LocalDate dataDate, String calcVersion, int total,
                                       LocalDateTime snapshotTime, String owner, int leaseSeconds) {
        UserBaselineRun existing = findRun(dataDate, calcVersion);
        if (existing == null) {
            UserBaselineRun created = UserBaselineRun.builder().dataDate(dataDate).calcVersion(calcVersion)
                    .status("RUNNING").totalUserCount(total).successUserCount(0).failedUserCount(0)
                    .snapshotTime(snapshotTime).startedAt(snapshotTime).build();
            try {
                runMapper.insertRunning(created, owner, leaseSeconds);
                return created;
            } catch (DuplicateKeyException ignored) {
                existing = findRun(dataDate, calcVersion);
            }
        }
        if (existing == null || "SUCCESS".equals(existing.getStatus())) {
            throw new IllegalStateException("业务日期" + dataDate + "、算法版本" + calcVersion
                    + "已经生成成功或运行记录异常，不允许重复生成。");
        }
        if (runMapper.claimForProcessing(existing.getId(), owner, leaseSeconds) != 1) {
            throw new IllegalStateException("业务日期" + dataDate + "、算法版本" + calcVersion
                    + "存在未过期执行者，不能并发恢复。");
        }
        return existing;
    }

    private UserBaselineRun findRun(LocalDate dataDate, String calcVersion) {
        return runMapper.selectOne(new LambdaQueryWrapper<UserBaselineRun>()
                .eq(UserBaselineRun::getDataDate, dataDate)
                .eq(UserBaselineRun::getCalcVersion, calcVersion).last("LIMIT 1"));
    }

    private BatchResult processBatch(Long runId, String executionOwner,
                                     LocalDate dataDate,
                                     BaselineCalendarDay targetDay,
                                     List<BaselineCalendarDay> historyDays,
                                     List<BaselineCalendarDay> sourceDays,
                                     List<AggregateUser> users,
                                     LocalDateTime snapshotTime) {
        List<String> consNos = users.stream().map(AggregateUser::getConsNo)
                .filter(value -> value != null && !value.isBlank()).distinct().toList();
        List<String> dataDates = sourceDays.stream().map(day -> day.getCalendarDate().toString()).distinct().toList();
        List<UserLoadPoint> sourcePoints = consNos.isEmpty() || dataDates.isEmpty()
                ? List.of() : sourceMapper.selectByConsNosAndDates(consNos, dataDates);
        Map<String, Map<LocalDate, Map<Integer, UserLoadPoint>>> loads = groupLoads(sourcePoints);
        Map<String, Set<LocalDate>> participationByUnit = participationDates(users, sourceDays);

        List<UserBaselineDetail> existingRows = consNos.isEmpty() ? List.of()
                : detailMapper.selectList(new LambdaQueryWrapper<UserBaselineDetail>()
                        .eq(UserBaselineDetail::getDataDate, dataDate)
                        .eq(UserBaselineDetail::getCalcVersion, properties.getCalcVersion())
                        .in(UserBaselineDetail::getConsNo, consNos));
        Map<String, List<UserBaselineDetail>> existingByUser = existingRows.stream()
                .collect(Collectors.groupingBy(UserBaselineDetail::getConsNo));

        int failed = 0;
        List<String> resumableUsers = new ArrayList<>(users.size());
        List<UserBaselineDetail> missingDetails = new ArrayList<>();
        for (AggregateUser user : users) {
            if (user.getConsNo() == null || user.getConsNo().isBlank()) {
                failed++;
                continue;
            }
            try {
                List<UserBaselineDetail> saved = existingByUser.getOrDefault(user.getConsNo(), List.of());
                Map<Integer, UserBaselineDetail> bySlot = detailBySlot(user.getConsNo(), saved);
                Set<Integer> missingSlots = new LinkedHashSet<>();
                for (int slot = 1; slot <= UserBaselineConstants.POINTS_PER_DAY; slot++) {
                    if (!bySlot.containsKey(slot)) {
                        missingSlots.add(slot);
                    }
                }
                if (!missingSlots.isEmpty()) {
                    UserRows calculated = calculateUser(runId, dataDate, targetDay, historyDays, user,
                            participationByUnit.getOrDefault(user.getUnitId(), Set.of()),
                            loads.getOrDefault(user.getConsNo(), Map.of()), snapshotTime, missingSlots);
                    calculated.details().forEach(detail -> bySlot.put(detail.getSlotNo(), detail));
                    missingDetails.addAll(calculated.details());
                }
                List<UserBaselineDetail> complete = bySlot.values().stream()
                        .sorted(Comparator.comparing(UserBaselineDetail::getSlotNo)).toList();
                if (complete.size() != UserBaselineConstants.POINTS_PER_DAY) {
                    throw new IllegalStateException("户号" + user.getConsNo() + "持久化明细不完整，实际"
                            + complete.size() + "点。");
                }
                resumableUsers.add(user.getConsNo());
            } catch (Exception e) {
                failed++;
                log.error("[用户基线] 户号生成失败，户号={}，业务日期={}", user.getConsNo(), dataDate, e);
            }
        }
        persistenceService.saveGeneratedDetails(runId, executionOwner, missingDetails);
        List<UserBaselineDetail> persisted = consNos.isEmpty() ? List.of()
                : detailMapper.selectList(new LambdaQueryWrapper<UserBaselineDetail>()
                        .eq(UserBaselineDetail::getDataDate, dataDate)
                        .eq(UserBaselineDetail::getCalcVersion, properties.getCalcVersion())
                        .in(UserBaselineDetail::getConsNo, consNos));
        Map<String, List<UserBaselineDetail>> persistedByUser = persisted.stream()
                .collect(Collectors.groupingBy(UserBaselineDetail::getConsNo));

        int success = 0;
        for (String consNo : resumableUsers) {
            try {
                List<UserBaselineDetail> rows = persistedByUser.getOrDefault(consNo, List.of());
                if (rows.size() != UserBaselineConstants.POINTS_PER_DAY) {
                    throw new IllegalStateException("户号" + consNo + "持久化明细不完整，实际" + rows.size() + "点。");
                }
                List<Long> pendingIds = rows.stream()
                        .filter(detail -> UserBaselineConstants.SYNC_PENDING.equals(detail.getSyncStatus()))
                        .map(UserBaselineDetail::getId)
                        .toList();
                publisher.publish(pendingIds);
                success++;
            } catch (Exception e) {
                failed++;
                log.error("[用户基线] 户号结果写入失败，户号={}，业务日期={}", consNo, dataDate, e);
            }
        }
        return new BatchResult(success, failed);
    }

    private UserRows calculateUser(Long runId,
                                   LocalDate dataDate,
                                   BaselineCalendarDay targetDay,
                                   List<BaselineCalendarDay> historyDays,
                                   AggregateUser user,
                                   Set<LocalDate> participationDates,
                                   Map<LocalDate, Map<Integer, UserLoadPoint>> loadsByDate,
                                   LocalDateTime snapshotTime,
                                   Set<Integer> slots) {
        List<UserBaselineDetail> details = new ArrayList<>(slots.size());
        for (Integer slot : slots) {
            List<TypicalSampleTrace> samples = sampleSelector.select(targetDay, historyDays,
                    participationDates, loadsByDate, slot);
            RawBaselineResult raw = pointCalculator.calculateRaw(samples);
            LocalDateTime targetTime = storageSupport.slotTime(dataDate, slot);
            String traceCode = storageSupport.traceCode(user.getConsNo(), dataDate, slot,
                    properties.getCalcVersion());
            UserBaselineDetail detail = UserBaselineDetail.builder()
                    .runId(runId)
                    .dataDate(dataDate)
                    .slotNo(slot)
                    .targetTime(targetTime)
                    .consNo(user.getConsNo())
                    .unitId(user.getUnitId())
                    .dayType(targetDay.getDayType())
                    .sampleDetailJson(JacksonUtils.toJson(raw.samples()))
                    .initialMeanMw(raw.initialMean())
                    .thresholdMw(raw.threshold())
                    .removedSampleJson(JacksonUtils.toJson(raw.removedSamples()))
                    .removedCount(raw.removedSamples().size())
                    .rawBaselineCalcMw(raw.rawCalculationValue())
                    .rawBaselineMw(raw.rawBaseline())
                    .needCorrection(raw.hasRemovedSample())
                    .finalBaselineMw(raw.rawBaseline())
                    .correctionStatus(raw.status())
                    .calculationRemark(raw.chineseRemark())
                    .traceCode(traceCode)
                    .snapshotTime(snapshotTime)
                    .generatedAt(LocalDateTime.now())
                    .syncStatus(UserBaselineConstants.SYNC_PENDING)
                    .calcVersion(properties.getCalcVersion())
                    .build();
            details.add(detail);
        }
        return new UserRows(details);
    }

    private Map<String, Map<LocalDate, Map<Integer, UserLoadPoint>>> groupLoads(List<UserLoadPoint> points) {
        Map<String, Map<LocalDate, Map<Integer, UserLoadPoint>>> grouped = new HashMap<>();
        if (points == null) {
            return grouped;
        }
        for (UserLoadPoint point : points) {
            if (point == null || point.getConsNo() == null || point.getDataDate() == null
                    || point.getTs() == null || point.getLoadMw() == null) {
                continue;
            }
            LocalDate sourceDate;
            try {
                sourceDate = LocalDate.parse(point.getDataDate());
            } catch (Exception e) {
                log.warn("[用户基线] 忽略非法数据日期，户号={}，数据日期={}", point.getConsNo(), point.getDataDate());
                continue;
            }
            int slot = storageSupport.slotOf(sourceDate, point.getTs().toLocalDateTime());
            if (slot < 1 || slot > UserBaselineConstants.POINTS_PER_DAY) {
                continue;
            }
            grouped.computeIfAbsent(point.getConsNo(), ignored -> new HashMap<>())
                    .computeIfAbsent(sourceDate, ignored -> new HashMap<>())
                    .put(slot, point);
        }
        return grouped;
    }

    private Map<String, Set<LocalDate>> participationDates(List<AggregateUser> users,
                                                           List<BaselineCalendarDay> sourceDays) {
        Set<String> unitIds = users.stream().map(AggregateUser::getUnitId)
                .filter(value -> value != null && !value.isBlank()).collect(Collectors.toSet());
        if (unitIds.isEmpty() || sourceDays.isEmpty()) {
            return Map.of();
        }
        Set<LocalDate> candidateDates = sourceDays.stream().map(BaselineCalendarDay::getCalendarDate)
                .collect(Collectors.toSet());
        LocalDate minDate = candidateDates.stream().min(LocalDate::compareTo).orElseThrow();
        LocalDate maxDate = candidateDates.stream().max(LocalDate::compareTo).orElseThrow();
        List<AdjustmentDay> rows = adjustmentDayMapper.selectList(new LambdaQueryWrapper<AdjustmentDay>()
                .in(AdjustmentDay::getUnitId, unitIds)
                .eq(AdjustmentDay::getParticipate, true)
                .between(AdjustmentDay::getAdjustDate, minDate, maxDate));
        Map<String, Set<LocalDate>> result = new HashMap<>();
        for (AdjustmentDay row : rows) {
            if (row.getAdjustDate() != null && candidateDates.contains(row.getAdjustDate())) {
                result.computeIfAbsent(row.getUnitId(), ignored -> new HashSet<>()).add(row.getAdjustDate());
            }
        }
        return result;
    }

    private List<BaselineCalendarDay> sourceDays(BaselineCalendarDay targetDay,
                                                 List<BaselineCalendarDay> historyDays) {
        LinkedHashSet<BaselineCalendarDay> result = new LinkedHashSet<>();
        if (UserBaselineConstants.DAY_HOLIDAY.equals(targetDay.getDayType())) {
            historyDays.stream().filter(day -> UserBaselineConstants.DAY_HOLIDAY.equals(day.getDayType()))
                    .forEach(result::add);
            historyDays.stream().filter(day -> UserBaselineConstants.DAY_RESTDAY.equals(day.getDayType()))
                    .limit(20).forEach(result::add);
        } else {
            long limit = UserBaselineConstants.DAY_WORKDAY.equals(targetDay.getDayType()) ? 30 : 20;
            historyDays.stream().filter(day -> targetDay.getDayType().equals(day.getDayType()))
                    .limit(limit).forEach(result::add);
        }
        return result.stream().sorted(Comparator.comparing(BaselineCalendarDay::getCalendarDate).reversed()).toList();
    }

    private LambdaQueryWrapper<AggregateUser> activeUserWrapper() {
        return new LambdaQueryWrapper<AggregateUser>()
                .eq(AggregateUser::getDeleted, false)
                .eq(AggregateUser::getStatus, true)
                .isNotNull(AggregateUser::getConsNo);
    }

    private Map<Integer, UserBaselineDetail> detailBySlot(String consNo, List<UserBaselineDetail> details) {
        Map<Integer, UserBaselineDetail> result = new HashMap<>();
        for (UserBaselineDetail detail : details) {
            if (detail == null || detail.getSlotNo() == null || detail.getSlotNo() < 1
                    || detail.getSlotNo() > UserBaselineConstants.POINTS_PER_DAY) {
                throw new IllegalStateException("户号" + consNo + "存在点序号非法的基线明细。");
            }
            if (result.put(detail.getSlotNo(), detail) != null) {
                throw new IllegalStateException("户号" + consNo + "第" + detail.getSlotNo() + "点存在重复基线明细。");
            }
        }
        return result;
    }

    private int reconcileCorrectionTasks(LocalDate dataDate, String calcVersion) {
        List<UserBaselineSlotSummary> summaries = detailMapper.selectCorrectionSlotSummaries(dataDate, calcVersion);
        int correctionPoints = 0;
        for (UserBaselineSlotSummary summary : summaries) {
            int slot = summary.getSlotNo();
            int totalCount = summary.getTotalCount() == null ? 0 : summary.getTotalCount();
            int waitingCount = summary.getWaitingCount() == null ? 0 : summary.getWaitingCount();
            if (slot < 1 || slot > UserBaselineConstants.POINTS_PER_DAY || totalCount <= 0) {
                throw new IllegalStateException("持久化修正明细的点位统计非法，点序号=" + slot + "，数量=" + totalCount);
            }
            correctionPoints += totalCount;
            LocalDateTime targetTime = storageSupport.slotTime(dataDate, slot);
            UserBaselineCorrectionTask task = taskMapper.selectOne(
                    new LambdaQueryWrapper<UserBaselineCorrectionTask>()
                            .eq(UserBaselineCorrectionTask::getDataDate, dataDate)
                            .eq(UserBaselineCorrectionTask::getSlotNo, slot)
                            .eq(UserBaselineCorrectionTask::getCalcVersion, calcVersion)
                            .last("LIMIT 1"));
            if (task == null) {
                task = UserBaselineCorrectionTask.builder()
                        .dataDate(dataDate)
                        .slotNo(slot)
                        .targetTime(targetTime)
                        .scheduledTime(targetTime)
                        .status(UserBaselineConstants.TASK_PENDING)
                        .totalCount(totalCount)
                        .successCount(0)
                        .failedCount(0)
                        .retryCount(0)
                        .calcVersion(calcVersion)
                        .build();
                taskMapper.insert(task);
                continue;
            }
            if (UserBaselineConstants.TASK_SUCCESS.equals(task.getStatus()) && waitingCount > 0) {
                if (taskMapper.reopenCompleted(task.getId(), totalCount) == 1) {
                    continue;
                }
            }
            taskMapper.updateTotalCount(task.getId(), totalCount);
        }
        return correctionPoints;
    }

    private record BatchResult(int success, int failed) {
    }

    private record UserRows(List<UserBaselineDetail> details) {
    }

}
