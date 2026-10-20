package com.vpp.platform.resource.baseline;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.vpp.framework.commons.JacksonUtils;
import com.vpp.platform.resource.entity.BaselineCalendarDay;
import com.vpp.platform.resource.repository.BaselineCalendarDayMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** 从随服务发布的本地JSON同步节假日日历到MySQL。 */
@Service
@RequiredArgsConstructor
public class HolidayCalendarSyncService {

    private static final List<Integer> LOCAL_CALENDAR_YEARS = List.of(2024, 2025, 2026);
    private static final String LOCAL_CALENDAR_PATH =
            "com/vpp/platform/resource/util/Calendar%d.json";

    private final BaselineCalendarDayMapper calendarDayMapper;

    public int syncAll() {
        int changed = 0;
        for (Integer year : LOCAL_CALENDAR_YEARS) {
            changed += syncYear(year);
        }
        return changed;
    }

    public int syncYear(int year) {
        String resourcePath = LOCAL_CALENDAR_PATH.formatted(year);
        byte[] body = readLocalCalendar(resourcePath);
        validateCalendarYear(body, year, resourcePath);
        String sourceHash = sha256(body);
        Map<LocalDate, HolidayException> exceptions = parseExceptions(body);
        List<BaselineCalendarDay> existingRows = calendarDayMapper.selectList(
                new LambdaQueryWrapper<BaselineCalendarDay>()
                        .between(BaselineCalendarDay::getCalendarDate,
                                LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31)));
        Map<LocalDate, BaselineCalendarDay> existingByDate = new HashMap<>();
        existingRows.forEach(row -> existingByDate.put(row.getCalendarDate(), row));

        int changed = 0;
        LocalDate date = LocalDate.of(year, 1, 1);
        LocalDate end = LocalDate.of(year, 12, 31);
        while (!date.isAfter(end)) {
            BaselineCalendarDay existing = existingByDate.get(date);
            if (existing != null && Boolean.TRUE.equals(existing.getManualOverride())) {
                date = date.plusDays(1);
                continue;
            }
            HolidayException exception = exceptions.get(date);
            BaselineCalendarDay row = existing == null ? new BaselineCalendarDay() : existing;
            fillCalendarRow(row, date, exception, "classpath:" + resourcePath,
                    String.valueOf(year), sourceHash);
            if (existing == null) {
                calendarDayMapper.insert(row);
            } else {
                calendarDayMapper.updateById(row);
            }
            changed++;
            date = date.plusDays(1);
        }
        return changed;
    }

    private void fillCalendarRow(BaselineCalendarDay row,
                                 LocalDate date,
                                 HolidayException exception,
                                 String sourceUrl,
                                 String sourceVersion,
                                 String sourceHash) {
        boolean weekend = date.getDayOfWeek() == DayOfWeek.SATURDAY
                || date.getDayOfWeek() == DayOfWeek.SUNDAY;
        boolean offDay = exception == null ? weekend : exception.offDay();
        boolean adjustedWorkday = exception != null && !exception.offDay() && weekend;
        String dayType;
        if (exception != null && exception.offDay()) {
            dayType = UserBaselineConstants.DAY_HOLIDAY;
        } else if (offDay) {
            dayType = UserBaselineConstants.DAY_RESTDAY;
        } else {
            dayType = UserBaselineConstants.DAY_WORKDAY;
        }
        row.setCalendarDate(date);
        row.setDayType(dayType);
        row.setIsOffDay(offDay);
        row.setIsAdjustedWorkday(adjustedWorkday);
        row.setHolidayName(exception == null ? null : exception.name());
        if (UserBaselineConstants.DAY_HOLIDAY.equals(dayType)) {
            row.setHolidayCode(hasText(exception.holidayCode()) ? exception.holidayCode() : exception.name());
            row.setHolidayGroupCode(hasText(exception.holidayGroupCode())
                    ? exception.holidayGroupCode() : exception.name());
            row.setFestivalTags(exception.festivalTags());
        } else {
            row.setHolidayCode(null);
            row.setHolidayGroupCode(null);
            row.setFestivalTags(null);
        }
        row.setSourceUrl(sourceUrl);
        row.setSourceVersion(sourceVersion);
        row.setSourceHash(sourceHash);
        row.setManualOverride(false);
        row.setRemark(exception == null
                ? "根据星期和本地节假日源生成。"
                : "根据本地节假日日历“" + exception.name() + "”安排生成。人工修订时请将人工覆盖标记设为是。");
    }

    private Map<LocalDate, HolidayException> parseExceptions(byte[] body) {
        JsonNode root = JacksonUtils.readTree(new String(body, StandardCharsets.UTF_8));
        JsonNode days = root.get("days");
        if (days == null || !days.isArray()) {
            throw new IllegalStateException("本地节假日日历缺少days数组。");
        }
        Map<LocalDate, HolidayException> result = new HashMap<>();
        for (JsonNode node : days) {
            if (node.get("date") == null || node.get("name") == null || node.get("isOffDay") == null) {
                throw new IllegalStateException("本地节假日日历存在缺少date、name或isOffDay的记录。");
            }
            LocalDate date = LocalDate.parse(node.get("date").asText());
            result.put(date, new HolidayException(node.get("name").asText(), node.get("isOffDay").asBoolean(),
                    text(node, "holidayCode"), text(node, "holidayGroupCode"), tags(node.get("festivalTags"))));
        }
        return result;
    }

    private String text(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        return value == null || value.isNull() ? null : value.asText();
    }

    private String tags(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isArray()) {
            throw new IllegalStateException("本地节假日日历的festivalTags必须是数组。");
        }
        List<String> tags = new java.util.ArrayList<>();
        for (JsonNode tag : value) {
            if (tag != null && !tag.asText().isBlank()) {
                tags.add(tag.asText().trim());
            }
        }
        return tags.isEmpty() ? null : String.join(",", tags);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private byte[] readLocalCalendar(String resourcePath) {
        Resource resource = new ClassPathResource(resourcePath);
        if (!resource.exists()) {
            throw new IllegalStateException("未找到本地节假日日历文件：" + resourcePath);
        }
        try (var inputStream = resource.getInputStream()) {
            return inputStream.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("读取本地节假日日历失败：" + resourcePath + "，" + e.getMessage(), e);
        }
    }

    private void validateCalendarYear(byte[] body, int expectedYear, String resourcePath) {
        JsonNode root = JacksonUtils.readTree(new String(body, StandardCharsets.UTF_8));
        if (root == null || !root.isObject()) {
            throw new IllegalStateException("本地节假日日历不是有效的JSON对象：" + resourcePath);
        }
        JsonNode year = root.get("year");
        if (year == null || !year.canConvertToInt() || year.asInt() != expectedYear) {
            throw new IllegalStateException("本地节假日日历年份与文件名不一致：" + resourcePath);
        }
    }

    private String sha256(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前JDK不支持SHA-256", e);
        }
    }

    private record HolidayException(String name,
                                    boolean offDay,
                                    String holidayCode,
                                    String holidayGroupCode,
                                    String festivalTags) {
    }
}
