package com.vpp.platform.resource.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.vpp.framework.commons.entity.SuperEntity;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDate;

/** 用户基线本地日历。 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("ra_calendar_day")
public class BaselineCalendarDay extends SuperEntity<Long> {

    /** 日历日期 */
    private LocalDate calendarDate;

    /** 日期类型：WORKDAY、RESTDAY、HOLIDAY */
    private String dayType;

    /** 是否休息日 */
    private Boolean isOffDay;

    /** 是否调休工作日 */
    private Boolean isAdjustedWorkday;

    /** 节假日中文名称 */
    private String holidayName;

    /** 节假日编码 */
    private String holidayCode;

    /** 跨年度同类节假日分组编码 */
    private String holidayGroupCode;

    /** 具体日期的节日标签，多个标签使用英文逗号分隔 */
    private String festivalTags;

    /** 数据来源地址 */
    private String sourceUrl;

    /** 数据来源版本 */
    private String sourceVersion;

    /** 数据来源SHA-256 */
    private String sourceHash;

    /** 是否人工覆盖 */
    private Boolean manualOverride;

    /** 中文备注 */
    private String remark;
}
