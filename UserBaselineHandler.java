package com.vpp.platform.resource.handler;

import com.vpp.platform.resource.baseline.UserBaselineGenerationService;
import com.vpp.platform.resource.baseline.HolidayCalendarSyncService;
import com.vpp.platform.resource.baseline.UserBaselineCorrectionService;
import com.vpp.platform.resource.baseline.model.UserBaselineJobResult;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/** 用户基线生成、到期修正和日历同步任务。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserBaselineHandler {

    private final UserBaselineGenerationService generationService;
    private final UserBaselineCorrectionService correctionService;
    private final HolidayCalendarSyncService calendarSyncService;

    /** 调度中心凌晨触发；参数为空计算当天，传yyyy-MM-dd用于指定业务日期。 */
    @XxlJob("generateDailyUserBaseline")
    public void generateDailyUserBaseline() {
        String param = XxlJobHelper.getJobParam();
        LocalDate dataDate = param == null || param.isBlank() ? LocalDate.now() : LocalDate.parse(param.trim());
        try {
            UserBaselineJobResult result = generationService.generate(dataDate);
            XxlJobHelper.log("[用户基线] 生成完成，业务日期={}，总户数={}，成功={}，失败={}，待修正点数={}",
                    dataDate, result.total(), result.success(), result.failed(), result.correctionPoints());
            if (result.failed() > 0) {
                XxlJobHelper.handleFail("部分户号生成失败，失败户数=" + result.failed());
            }
        } catch (Exception e) {
            log.error("[用户基线] 生成失败，业务日期={}", dataDate, e);
            XxlJobHelper.handleFail(e.getMessage());
        }
    }

    /** 调度中心每分钟触发，直接执行已经到达目标时刻的修正任务。 */
    @XxlJob("correctDueUserBaseline")
    public void correctDueUserBaseline() {
        try {
            int count = correctionService.correctDueTasks();
            XxlJobHelper.log("[用户基线] 本次完成到期修正任务{}条", count);
        } catch (Exception e) {
            log.error("[用户基线] 执行到期修正任务失败", e);
            XxlJobHelper.handleFail(e.getMessage());
        }
    }

    /** 同步全部本地日历文件；传年份参数时只同步指定年份。 */
    @XxlJob("syncUserBaselineCalendar")
    public void syncUserBaselineCalendar() {
        String param = XxlJobHelper.getJobParam();
        try {
            int changed = 0;
            if (param != null && !param.isBlank()) {
                changed = calendarSyncService.syncYear(Integer.parseInt(param.trim()));
            } else {
                changed = calendarSyncService.syncAll();
            }
            XxlJobHelper.log("[用户基线] 节假日日历同步完成，写入或更新{}天", changed);
        } catch (Exception e) {
            log.error("[用户基线] 节假日日历同步失败", e);
            XxlJobHelper.handleFail(e.getMessage());
        }
    }
}
