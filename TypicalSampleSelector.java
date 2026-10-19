package com.vpp.platform.resource.baseline;

import com.vpp.platform.resource.baseline.model.TypicalSampleTrace;
import com.vpp.platform.resource.baseline.model.UserLoadPoint;
import com.vpp.platform.resource.entity.BaselineCalendarDay;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 按已确认优先级为单户单点选择典型日样本。 */
@Component
public class TypicalSampleSelector {

    private static final int MAX_SAMPLES = 5;
    private static final int WORKDAY_SCOPE = 30;
    private static final int RESTDAY_SCOPE = 20;

    public List<TypicalSampleTrace> select(BaselineCalendarDay targetDay,
                                           List<BaselineCalendarDay> historyDaysDesc,
                                           Set<LocalDate> participationDates,
                                           Map<LocalDate, Map<Integer, UserLoadPoint>> loadsByDate,
                                           int slotNo) {
        if (UserBaselineConstants.DAY_HOLIDAY.equals(targetDay.getDayType())) {
            return selectHoliday(targetDay, historyDaysDesc, participationDates, loadsByDate, slotNo);
        }
        int scopeSize = UserBaselineConstants.DAY_WORKDAY.equals(targetDay.getDayType())
                ? WORKDAY_SCOPE : RESTDAY_SCOPE;
        List<BaselineCalendarDay> scope = historyDaysDesc.stream()
                .filter(day -> targetDay.getDayType().equals(day.getDayType()))
                .limit(scopeSize)
                .toList();
        return selectOrdinary(scope, participationDates, loadsByDate, slotNo, MAX_SAMPLES);
    }

    private List<TypicalSampleTrace> selectOrdinary(List<BaselineCalendarDay> scope,
                                                    Set<LocalDate> participationDates,
                                                    Map<LocalDate, Map<Integer, UserLoadPoint>> loadsByDate,
                                                    int slotNo,
                                                    int maxSamples) {
        List<BaselineCalendarDay> normalDays = scope.stream()
                .filter(day -> !participationDates.contains(day.getCalendarDate()))
                .filter(day -> loadsByDate.containsKey(day.getCalendarDate()))
                .toList();
        List<BaselineCalendarDay> participationDays = scope.stream()
                .filter(day -> participationDates.contains(day.getCalendarDate()))
                .filter(day -> loadsByDate.containsKey(day.getCalendarDate()))
                .toList();

        List<TypicalSampleTrace> result = new ArrayList<>();
        appendActual(result, normalDays, loadsByDate, slotNo, "正常真实值", maxSamples);
        appendFitted(result, normalDays, scope, loadsByDate, slotNo, "正常拟合值", maxSamples);
        appendActual(result, participationDays, loadsByDate, slotNo, "参与日真实值", maxSamples);
        appendFitted(result, participationDays, scope, loadsByDate, slotNo, "参与日拟合值", maxSamples);
        return result;
    }

    private List<TypicalSampleTrace> selectHoliday(BaselineCalendarDay targetDay,
                                                   List<BaselineCalendarDay> historyDaysDesc,
                                                   Set<LocalDate> participationDates,
                                                   Map<LocalDate, Map<Integer, UserLoadPoint>> loadsByDate,
                                                   int slotNo) {
        String targetGroup = holidayGroup(targetDay);
        List<BaselineCalendarDay> lastYearSameHoliday = historyDaysDesc.stream()
                .filter(day -> UserBaselineConstants.DAY_HOLIDAY.equals(day.getDayType()))
                .filter(day -> day.getCalendarDate().getYear() == targetDay.getCalendarDate().getYear() - 1)
                .filter(day -> targetGroup.equals(holidayGroup(day)))
                .toList();

        List<List<BaselineCalendarDay>> groups = new ArrayList<>();
        if (!lastYearSameHoliday.isEmpty()) {
            groups.add(lastYearSameHoliday);
        }
        Map<String, List<BaselineCalendarDay>> otherGroups = new LinkedHashMap<>();
        historyDaysDesc.stream()
                .filter(day -> UserBaselineConstants.DAY_HOLIDAY.equals(day.getDayType()))
                .filter(day -> day.getCalendarDate().getYear() == targetDay.getCalendarDate().getYear())
                .filter(day -> !targetGroup.equals(holidayGroup(day)))
                .forEach(day -> otherGroups.computeIfAbsent(holidayGroup(day), ignored -> new ArrayList<>()).add(day));
        groups.addAll(otherGroups.values().stream()
                .sorted(Comparator.comparing((List<BaselineCalendarDay> days) ->
                        days.get(0).getCalendarDate()).reversed())
                .toList());

        List<BaselineCalendarDay> allHolidayDays = historyDaysDesc.stream()
                .filter(day -> UserBaselineConstants.DAY_HOLIDAY.equals(day.getDayType()))
                .toList();
        for (List<BaselineCalendarDay> group : groups) {
            List<BaselineCalendarDay> usableDays = group.stream()
                    .filter(day -> !participationDates.contains(day.getCalendarDate()))
                    .filter(day -> loadsByDate.containsKey(day.getCalendarDate()))
                    .toList();
            List<TypicalSampleTrace> samples = new ArrayList<>();
            appendActual(samples, usableDays, loadsByDate, slotNo, "节假日真实值", Integer.MAX_VALUE);
            appendFitted(samples, usableDays, allHolidayDays, loadsByDate, slotNo,
                    "节假日拟合值", Integer.MAX_VALUE);
            if (!samples.isEmpty()) {
                return samples;
            }
        }

        List<BaselineCalendarDay> restScope = historyDaysDesc.stream()
                .filter(day -> UserBaselineConstants.DAY_RESTDAY.equals(day.getDayType()))
                .limit(RESTDAY_SCOPE)
                .toList();
        return selectOrdinary(restScope, participationDates, loadsByDate, slotNo, MAX_SAMPLES);
    }

    private void appendActual(List<TypicalSampleTrace> result,
                              List<BaselineCalendarDay> targetDays,
                              Map<LocalDate, Map<Integer, UserLoadPoint>> loadsByDate,
                              int slotNo,
                              String category,
                              int maxSamples) {
        Set<LocalDate> existingTargets = existingTargets(result);
        for (BaselineCalendarDay day : targetDays) {
            if (result.size() >= maxSamples || existingTargets.contains(day.getCalendarDate())) {
                continue;
            }
            UserLoadPoint point = loadsByDate.getOrDefault(day.getCalendarDate(), Map.of()).get(slotNo);
            if (point != null && point.getLoadMw() != null) {
                result.add(new TypicalSampleTrace(day.getCalendarDate(), day.getCalendarDate(), slotNo,
                        point.getLoadMw(), category, false, chineseSourceType(point.getSrcType())));
                existingTargets.add(day.getCalendarDate());
            }
        }
    }

    private void appendFitted(List<TypicalSampleTrace> result,
                              List<BaselineCalendarDay> targetDays,
                              List<BaselineCalendarDay> sourceScope,
                              Map<LocalDate, Map<Integer, UserLoadPoint>> loadsByDate,
                              int slotNo,
                              String category,
                              int maxSamples) {
        Set<LocalDate> existingTargets = existingTargets(result);
        for (BaselineCalendarDay targetDay : targetDays) {
            if (result.size() >= maxSamples || existingTargets.contains(targetDay.getCalendarDate())) {
                continue;
            }
            UserLoadPoint direct = loadsByDate.getOrDefault(targetDay.getCalendarDate(), Map.of()).get(slotNo);
            if (direct != null && direct.getLoadMw() != null) {
                continue;
            }
            for (BaselineCalendarDay sourceDay : sourceScope) {
                if (!sourceDay.getCalendarDate().isBefore(targetDay.getCalendarDate())) {
                    continue;
                }
                UserLoadPoint source = loadsByDate.getOrDefault(sourceDay.getCalendarDate(), Map.of()).get(slotNo);
                if (source != null && source.getLoadMw() != null) {
                    result.add(new TypicalSampleTrace(targetDay.getCalendarDate(), sourceDay.getCalendarDate(),
                            slotNo, source.getLoadMw(), category, true, chineseSourceType(source.getSrcType())));
                    existingTargets.add(targetDay.getCalendarDate());
                    break;
                }
            }
        }
    }

    private Set<LocalDate> existingTargets(List<TypicalSampleTrace> result) {
        Set<LocalDate> dates = new LinkedHashSet<>();
        result.forEach(sample -> dates.add(sample.targetDate()));
        return dates;
    }

    private String holidayGroup(BaselineCalendarDay day) {
        if (day.getHolidayGroupCode() != null && !day.getHolidayGroupCode().isBlank()) {
            return day.getHolidayGroupCode();
        }
        if (day.getHolidayCode() != null && !day.getHolidayCode().isBlank()) {
            return day.getHolidayCode();
        }
        return day.getHolidayName() == null ? day.getCalendarDate().toString() : day.getHolidayName();
    }

    private String chineseSourceType(String sourceType) {
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
}
