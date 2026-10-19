package com.vpp.platform.resource.baseline;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.vpp.framework.commons.JacksonUtils;
import com.vpp.platform.resource.baseline.model.RawBaselineResult;
import com.vpp.platform.resource.baseline.model.TypicalSampleTrace;
import com.vpp.platform.resource.baseline.model.UserBaselineJobResult;
import com.vpp.platform.resource.baseline.model.UserLoadPoint;
import com.vpp.platform.resource.entity.AdjustmentDay;
import com.vpp.platform.resource.entity.AggregateUser;
import com.vpp.platform.resource.entity.BaselineCalendarDay;
import com.vpp.platform.resource.entity.UserBaselineCorrectionTask;
import com.vpp.platform.resource.entity.UserBaselineDetail;
import com.vpp.platform.resource.entity.UserBaselineLoad;
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

import java.sql.Timestamp;
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
    private final UserBaselineLoadStore loadStore;
    private final TypicalSampleSelector sampleSelector;
    private final UserBaselinePointCalculator pointCalculator;
    private final UserBaselineStorageSupport storageSupport;
    private final UserBaselineProperties properties;

    public UserBaselineJobResult generate(LocalDate dataDate) {
        String calcVersion = properties.getCalcVersion();
        UserBaselineRun existing = runMapper.selectOne(new LambdaQueryWrapper<UserBaselineRun>()
                .eq(UserBaselineRun::getDataDate, dataDate)
                .eq(UserBaselineRun::getCalcVersion, calcVersion)
                .last("LIMIT 1"));
        if (existing != null) {
            throw new IllegalStateException("业务日期" + dataDate + "、算法版本" + calcVersion
                    + "已存在生成记录，为避免覆盖已发布结果，本次不重复生成。");
        }

        BaselineCalendarDay targetDay = calendarDayMapper.selectOne(
                new LambdaQueryWrapper<BaselineCalendarDay>()
                        .eq(BaselineCalendarDay::getCalendarDate, dataDate)
                        .last("LIMIT 1"));
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

        long totalLong = aggregateUserMapper.selectCount(activeUserWrapper());
        int total = Math.toIntExact(totalLong);
        LocalDateTime snapshotTime = LocalDateTime.now();
        UserBaselineRun run = UserBaselineRun.builder()
                .dataDate(dataDate)
                .calcVersion(calcVersion)
                .status("RUNNING")
                .totalUserCount(total)
                .successUserCount(0)
                .failedUserCount(0)
                .snapshotTime(snapshotTime)
                .startedAt(snapshotTime)
                .build();
        runMapper.insert(run);

        int success = 0;
        int failed = 0;
        int[] correctionCounts = new int[UserBaselineConstants.POINTS_PER_DAY + 1];
        long lastId = 0L;
        int batchSize = Math.max(1, properties.getUserBatchSize());
        try {
            while (true) {
                LambdaQueryWrapper<AggregateUser> wrapper = activeUserWrapper()
                        .gt(AggregateUser::getId, lastId)
                        .orderByAsc(AggregateUser::getId)
                        .last("LIMIT " + batchSize);
                List<AggregateUser> users = aggregateUserMapper.selectList(wrapper);
                if (users == null || users.isEmpty()) {
                    break;
                }
                BatchResult batchResult = processBatch(run.getId(), dataDate, targetDay, historyDays,
                        sourceDays, users, snapshotTime, correctionCounts);
                success += batchResult.success();
                failed += batchResult.failed();
                lastId = users.get(users.size() - 1).getId();
            }
            createCorrectionTasks(dataDate, correctionCounts, calcVersion);
            run.setSuccessUserCount(success);
            run.setFailedUserCount(failed);
            run.setStatus(failed == 0 ? "SUCCESS" : "PARTIAL_FAILED");
            run.setFinishedAt(LocalDateTime.now());
            runMapper.updateById(run);
        } catch (Exception e) {
            run.setSuccessUserCount(success);
            run.setFailedUserCount(Math.max(failed, total - success));
            run.setStatus("FAILED");
            run.setFinishedAt(LocalDateTime.now());
            run.setErrorMessage("用户基线生成失败：" + e.getMessage());
            runMapper.updateById(run);
            throw e;
        }
        int correctionPoints = java.util.Arrays.stream(correctionCounts).sum();
        return new UserBaselineJobResult(total, success, failed, correctionPoints);
    }

    private BatchResult processBatch(Long runId,
                                     LocalDate dataDate,
                                     BaselineCalendarDay targetDay,
                                     List<BaselineCalendarDay> historyDays,
                                     List<BaselineCalendarDay> sourceDays,
                                     List<AggregateUser> users,
                                     LocalDateTime snapshotTime,
                                     int[] correctionCounts) {
        List<String> consNos = users.stream().map(AggregateUser::getConsNo)
                .filter(value -> value != null && !value.isBlank()).distinct().toList();
        List<String> dataDates = sourceDays.stream().map(day -> day.getCalendarDate().toString()).distinct().toList();
        List<UserLoadPoint> sourcePoints = consNos.isEmpty() || dataDates.isEmpty()
                ? List.of() : sourceMapper.selectByConsNosAndDates(consNos, dataDates);
        Map<String, Map<LocalDate, Map<Integer, UserLoadPoint>>> loads = groupLoads(sourcePoints);
        Map<String, Set<LocalDate>> participationByUnit = participationDates(users, sourceDays);

        int success = 0;
        int failed = 0;
        List<UserRows> calculatedUsers = new ArrayList<>(users.size());
        for (AggregateUser user : users) {
            if (user.getConsNo() == null || user.getConsNo().isBlank()) {
                failed++;
                continue;
            }
            try {
                UserRows rows = calculateUser(runId, dataDate, targetDay, historyDays, user,
                        participationByUnit.getOrDefault(user.getUnitId(), Set.of()),
                        loads.getOrDefault(user.getConsNo(), Map.of()), snapshotTime);
                calculatedUsers.add(rows);
            } catch (Exception e) {
                failed++;
                log.error("[用户基线] 户号生成失败，户号={}，业务日期={}", user.getConsNo(), dataDate, e);
            }
        }
        insertDetails(calculatedUsers.stream().flatMap(rows -> rows.details().stream()).toList());
        for (UserRows rows : calculatedUsers) {
            String consNo = rows.details().getFirst().getConsNo();
            try {
                loadStore.replaceUserDate(consNo, dataDate, rows.loads());
                rows.details().stream().filter(detail -> Boolean.TRUE.equals(detail.getNeedCorrection()))
                        .forEach(detail -> correctionCounts[detail.getSlotNo()]++);
                success++;
            } catch (Exception e) {
                failed++;
                log.error("[用户基线] 户号结果写入失败，户号={}，业务日期={}", consNo, dataDate, e);
            }
        }
        return new BatchResult(success, failed);
    }

    private void insertDetails(List<UserBaselineDetail> details) {
        int writeBatchSize = Math.max(1, properties.getDetailWriteBatchSize());
        for (int start = 0; start < details.size(); start += writeBatchSize) {
            detailMapper.insert(details.subList(start, Math.min(start + writeBatchSize, details.size())));
        }
    }

    private UserRows calculateUser(Long runId,
                                   LocalDate dataDate,
                                   BaselineCalendarDay targetDay,
                                   List<BaselineCalendarDay> historyDays,
                                   AggregateUser user,
                                   Set<LocalDate> participationDates,
                                   Map<LocalDate, Map<Integer, UserLoadPoint>> loadsByDate,
                                   LocalDateTime snapshotTime) {
        List<UserBaselineDetail> details = new ArrayList<>(UserBaselineConstants.POINTS_PER_DAY);
        List<UserBaselineLoad> resultRows = new ArrayList<>(UserBaselineConstants.POINTS_PER_DAY);
        for (int slot = 1; slot <= UserBaselineConstants.POINTS_PER_DAY; slot++) {
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
                    .calcVersion(properties.getCalcVersion())
                    .build();
            details.add(detail);
            resultRows.add(toLoadRow(detail));
        }
        return new UserRows(details, resultRows);
    }

    private UserBaselineLoad toLoadRow(UserBaselineDetail detail) {
        return UserBaselineLoad.builder()
                .ts(Timestamp.valueOf(detail.getTargetTime()))
                .slotNo(detail.getSlotNo())
                .rawBaselineE6(storageSupport.toScaledLong(detail.getRawBaselineMw()))
                .finalBaselineE6(storageSupport.toScaledLong(detail.getFinalBaselineMw()))
                .correctionStatus(detail.getCorrectionStatus())
                .calculationRemark(detail.getCalculationRemark())
                .traceId(detail.getTraceCode())
                .calcVersion(detail.getCalcVersion())
                .generatedAt(Timestamp.valueOf(detail.getGeneratedAt()))
                .consNo(detail.getConsNo())
                .dataDate(detail.getDataDate().toString())
                .build();
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

    private void createCorrectionTasks(LocalDate dataDate, int[] correctionCounts, String calcVersion) {
        for (int slot = 1; slot <= UserBaselineConstants.POINTS_PER_DAY; slot++) {
            if (correctionCounts[slot] <= 0) {
                continue;
            }
            LocalDateTime targetTime = storageSupport.slotTime(dataDate, slot);
            UserBaselineCorrectionTask task = UserBaselineCorrectionTask.builder()
                    .dataDate(dataDate)
                    .slotNo(slot)
                    .targetTime(targetTime)
                    .scheduledTime(targetTime)
                    .status(UserBaselineConstants.TASK_PENDING)
                    .totalCount(correctionCounts[slot])
                    .successCount(0)
                    .failedCount(0)
                    .retryCount(0)
                    .calcVersion(calcVersion)
                    .build();
            taskMapper.insert(task);
        }
    }

    private record BatchResult(int success, int failed) {
    }

    private record UserRows(List<UserBaselineDetail> details, List<UserBaselineLoad> loads) {
    }
}
