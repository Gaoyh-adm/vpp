package com.vpp.platform.resource.baseline;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.vpp.framework.commons.JacksonUtils;
import com.vpp.platform.resource.baseline.model.InitialBaselineSeed;
import com.vpp.platform.resource.baseline.model.RawBaselineResult;
import com.vpp.platform.resource.baseline.model.TypicalSampleTrace;
import com.vpp.platform.resource.baseline.model.UserLoadPoint;
import com.vpp.platform.resource.entity.AdjustmentDay;
import com.vpp.platform.resource.entity.BaselineCalendarDay;
import com.vpp.platform.resource.entity.UserBaselineDetail;
import com.vpp.platform.resource.entity.UserBaselineInitialWindow;
import com.vpp.platform.resource.repository.AdjustmentDayMapper;
import com.vpp.platform.resource.repository.BaselineCalendarDayMapper;
import com.vpp.platform.resource.repository.UserBaselineInitialWindowMapper;
import com.vpp.platform.resource.repository.UserBaselineSourceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 计算并读取户号首次修正所需的16点历史原始基线。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserBaselineInitialWindowService {

    private final UserBaselineInitialWindowMapper initialWindowMapper;
    private final BaselineCalendarDayMapper calendarDayMapper;
    private final AdjustmentDayMapper adjustmentDayMapper;
    private final UserBaselineSourceMapper sourceMapper;
    private final TypicalSampleSelector sampleSelector;
    private final UserBaselinePointCalculator pointCalculator;
    private final UserBaselineStorageSupport storageSupport;
    /**
     * 查询已经完成的首次初始化窗口，键使用完整历史时点。
     *
     * @param consNos 户号集合
     * @param calcVersion 算法版本
     * @return 户号及其完整历史时点初始化值
     */
    public Map<String, Map<LocalDateTime, InitialBaselineSeed>> findByUsers(
            List<String> consNos, String calcVersion) {
        if (consNos == null || consNos.isEmpty()) {
            return Map.of();
        }
        List<UserBaselineInitialWindow> rows = initialWindowMapper.selectList(
                new LambdaQueryWrapper<UserBaselineInitialWindow>()
                        .eq(UserBaselineInitialWindow::getCalcVersion, calcVersion)
                        .in(UserBaselineInitialWindow::getConsNo, consNos));
        Map<String, Map<LocalDateTime, InitialBaselineSeed>> result = new HashMap<>();
        for (UserBaselineInitialWindow row : rows) {
            result.put(row.getConsNo(), seedMap(row));
        }
        return result;
    }

    /**
     * 只为尚无初始化记录的户号计算完整窗口；持久化由持有任务执行权的短事务完成。
     *
     * @param details 需要判断首次初始化的修正明细
     * @param firstTargetTime 首次修正目标时刻
     * @param calcVersion 算法版本
     * @return 本次需要首次保存的初始化窗口
     * @throws IllegalStateException 历史点缺少日历或初始化内容不完整时抛出
     */
    public List<UserBaselineInitialWindow> buildMissingWindows(
            List<UserBaselineDetail> details, LocalDateTime firstTargetTime, String calcVersion) {
        if (details == null || details.isEmpty()) {
            return List.of();
        }
        List<String> consNos = details.stream().map(UserBaselineDetail::getConsNo)
                .filter(value -> value != null && !value.isBlank()).distinct().toList();
        Map<String, Map<LocalDateTime, InitialBaselineSeed>> existing = findByUsers(consNos, calcVersion);
        List<UserBaselineDetail> pending = details.stream()
                .filter(detail -> !existing.containsKey(detail.getConsNo()))
                .toList();
        if (pending.isEmpty()) {
            return List.of();
        }

        List<HistoricalPoint> points = historicalPoints(firstTargetTime);
        Set<LocalDate> targetDates = points.stream().map(HistoricalPoint::dataDate).collect(Collectors.toSet());
        List<BaselineCalendarDay> targetRows = calendarDayMapper.selectList(
                new LambdaQueryWrapper<BaselineCalendarDay>()
                        .in(BaselineCalendarDay::getCalendarDate, targetDates));
        Map<LocalDate, BaselineCalendarDay> targetByDate = targetRows.stream().collect(Collectors.toMap(
                BaselineCalendarDay::getCalendarDate, day -> day, (left, right) -> left));
        for (LocalDate targetDate : targetDates) {
            if (!targetByDate.containsKey(targetDate)) {
                throw new IllegalStateException("首次初始化历史点" + targetDate + "尚未维护日历，无法计算B0。");
            }
        }

        LocalDate maxTargetDate = targetDates.stream().max(LocalDate::compareTo).orElseThrow();
        List<BaselineCalendarDay> allHistoryDays = calendarDayMapper.selectList(
                new LambdaQueryWrapper<BaselineCalendarDay>()
                        .lt(BaselineCalendarDay::getCalendarDate, maxTargetDate)
                        .orderByDesc(BaselineCalendarDay::getCalendarDate));
        Map<LocalDate, List<BaselineCalendarDay>> historyByTargetDate = new HashMap<>();
        Set<BaselineCalendarDay> allSourceDays = new LinkedHashSet<>();
        for (LocalDate targetDate : targetDates) {
            List<BaselineCalendarDay> history = allHistoryDays.stream()
                    .filter(day -> day.getCalendarDate().isBefore(targetDate))
                    .toList();
            historyByTargetDate.put(targetDate, history);
            allSourceDays.addAll(sourceDays(targetByDate.get(targetDate), history));
        }

        List<String> sourceDateStrings = allSourceDays.stream()
                .map(day -> day.getCalendarDate().toString()).distinct().toList();
        List<UserLoadPoint> sourceRows = sourceDateStrings.isEmpty()
                ? List.of() : sourceMapper.selectByConsNosAndDates(consNos, sourceDateStrings);
        Map<String, Map<LocalDate, Map<Integer, UserLoadPoint>>> loads = groupLoads(sourceRows);
        Map<String, Set<LocalDate>> participationByUnit = participationDates(pending, allSourceDays);

        List<UserBaselineInitialWindow> inserts = new ArrayList<>(pending.size());
        LocalDateTime initializedAt = LocalDateTime.now();
        for (UserBaselineDetail detail : pending) {
            List<InitialBaselineSeed> seeds = new ArrayList<>(UserBaselineConstants.WINDOW_POINTS);
            Map<LocalDate, Map<Integer, UserLoadPoint>> userLoads =
                    loads.getOrDefault(detail.getConsNo(), Map.of());
            Set<LocalDate> participationDates = participationByUnit.getOrDefault(detail.getUnitId(), Set.of());
            for (HistoricalPoint point : points) {
                BaselineCalendarDay targetDay = targetByDate.get(point.dataDate());
                List<TypicalSampleTrace> samples = sampleSelector.select(targetDay,
                        historyByTargetDate.get(point.dataDate()), participationDates, userLoads, point.slotNo());
                RawBaselineResult raw = pointCalculator.calculateRaw(samples);
                seeds.add(new InitialBaselineSeed(
                        point.pointTime(), point.dataDate(), point.slotNo(), raw));
            }
            UserBaselineInitialWindow row = UserBaselineInitialWindow.builder()
                    .consNo(detail.getConsNo())
                    .calcVersion(calcVersion)
                    .firstTargetTime(firstTargetTime)
                    .seedWindowJson(JacksonUtils.toJson(seeds))
                    .initializedAt(initializedAt)
                    .remark("首次启动历史最终基线不足，已按16个完整历史时点各自所属业务日期计算并保存B0；"
                            + "后续仅滚动使用该初始化窗口，不再次初始化。")
                    .build();
            inserts.add(row);
        }
        return inserts;
    }

    private Map<LocalDateTime, InitialBaselineSeed> seedMap(UserBaselineInitialWindow row) {
        List<InitialBaselineSeed> seeds = JacksonUtils.readValue(row.getSeedWindowJson(),
                new TypeReference<List<InitialBaselineSeed>>() { });
        if (seeds == null) {
            throw new IllegalStateException("户号" + row.getConsNo() + "的首次初始化窗口内容为空。");
        }
        return seeds.stream().collect(Collectors.toMap(
                InitialBaselineSeed::pointTime, seed -> seed, (left, right) -> left));
    }

    private List<HistoricalPoint> historicalPoints(LocalDateTime targetTime) {
        List<HistoricalPoint> result = new ArrayList<>(UserBaselineConstants.WINDOW_POINTS);
        for (int i = UserBaselineConstants.WINDOW_POINTS; i >= 1; i--) {
            LocalDateTime pointTime = targetTime.minusMinutes(i * 15L);
            if (LocalTime.MIDNIGHT.equals(pointTime.toLocalTime())) {
                result.add(new HistoricalPoint(pointTime, pointTime.toLocalDate().minusDays(1),
                        UserBaselineConstants.POINTS_PER_DAY));
            } else {
                int minute = pointTime.getHour() * 60 + pointTime.getMinute();
                result.add(new HistoricalPoint(pointTime, pointTime.toLocalDate(), minute / 15));
            }
        }
        return result;
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
                log.warn("[用户基线] 首次初始化忽略非法数据日期，户号={}，数据日期={}",
                        point.getConsNo(), point.getDataDate());
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

    private Map<String, Set<LocalDate>> participationDates(List<UserBaselineDetail> details,
                                                           Set<BaselineCalendarDay> sourceDays) {
        Set<String> unitIds = details.stream().map(UserBaselineDetail::getUnitId)
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
        Set<BaselineCalendarDay> result = new LinkedHashSet<>();
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
        return result.stream().sorted(Comparator.comparing(BaselineCalendarDay::getCalendarDate).reversed())
                .toList();
    }

    private record HistoricalPoint(LocalDateTime pointTime, LocalDate dataDate, int slotNo) {
    }
}
