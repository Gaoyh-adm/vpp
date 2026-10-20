package com.vpp.platform.resource.repository;

import com.vpp.framework.db.mybatisplus.ext.SuperMapper;
import com.vpp.platform.resource.baseline.model.UserBaselineSlotSummary;
import com.vpp.platform.resource.entity.UserBaselineDetail;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 用户基线审计明细映射器。 */
@Repository
public interface UserBaselineDetailMapper extends SuperMapper<UserBaselineDetail> {

    @Update("UPDATE ra_user_baseline_detail SET sync_status='SYNCED',synced_at=NOW(3),"
            + "last_modify_time=NOW(3) WHERE id=#{id} AND calc_version=#{calcVersion} AND deleted=0 "
            + "AND correction_status=#{sentStatus} AND correction_status IN "
            + "('NOT_REQUIRED','NO_BASELINE','CORRECTED','UNCORRECTABLE') AND sync_status='PENDING'")
    int confirmPublished(@Param("id") Long id, @Param("calcVersion") String calcVersion,
                         @Param("sentStatus") String sentStatus);

    @Select("SELECT * FROM ra_user_baseline_detail WHERE id=#{id} AND deleted=0 FOR UPDATE")
    UserBaselineDetail selectForUpdate(@Param("id") Long id);

    @Update("UPDATE ra_user_baseline_detail SET actual_window_json=#{detail.actualWindowJson},"
            + "baseline_window_json=#{detail.baselineWindowJson},actual_window_mean_mw=#{detail.actualWindowMeanMw},"
            + "baseline_window_mean_mw=#{detail.baselineWindowMeanMw},k_value=#{detail.kValue},"
            + "final_baseline_mw=#{detail.finalBaselineMw},correction_status=#{detail.correctionStatus},"
            + "calculation_remark=#{detail.calculationRemark},corrected_at=#{detail.correctedAt},"
            + "sync_status='PENDING',synced_at=NULL,last_modify_time=NOW(3) WHERE id=#{detail.id} "
            + "AND calc_version=#{detail.calcVersion} AND correction_status='WAITING' AND deleted=0")
    int updateWaitingCorrection(@Param("detail") UserBaselineDetail detail);

    @Select({"<script>",
            "SELECT DISTINCT cons_no FROM ra_user_baseline_detail WHERE deleted=0 AND calc_version=#{calcVersion}",
            "AND target_time&gt;=#{startTime} AND target_time&lt;#{targetTime}",
            "AND (correction_status='WAITING' OR (correction_status IN",
            "('NOT_REQUIRED','NO_BASELINE','CORRECTED','UNCORRECTABLE') AND sync_status&lt;&gt;'SYNCED'))",
            "AND cons_no IN <foreach collection='consNos' item='consNo' open='(' separator=',' close=')'>#{consNo}</foreach>",
            "</script>"})
    List<String> selectUnreadyHistoryUsers(@Param("consNos") List<String> consNos,
                                            @Param("calcVersion") String calcVersion,
                                            @Param("startTime") LocalDateTime startTime,
                                            @Param("targetTime") LocalDateTime targetTime);

    /** 按点位汇总需要修正的持久化明细，用于恢复时补建或校准任务。 */
    @Select("SELECT slot_no, COUNT(*) AS total_count, "
            + "SUM(CASE WHEN correction_status='WAITING' THEN 1 ELSE 0 END) AS waiting_count "
            + "FROM ra_user_baseline_detail WHERE data_date=#{dataDate} AND calc_version=#{calcVersion} "
            + "AND need_correction=1 AND deleted=0 GROUP BY slot_no ORDER BY slot_no")
    List<UserBaselineSlotSummary> selectCorrectionSlotSummaries(@Param("dataDate") LocalDate dataDate,
                                                                 @Param("calcVersion") String calcVersion);
}
