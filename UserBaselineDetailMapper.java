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

    /**
     * 原始96点完整写入TDengine后，确认该户当日明细已经同步。
     *
     * @param dataDate 业务日期
     * @param consNo 户号
     * @param calcVersion 算法版本
     * @param syncedAt 同步完成时间
     * @return 更新行数
     */
    @Update("UPDATE ra_user_baseline_detail SET sync_status='SYNCED', synced_at=#{syncedAt}, "
            + "last_modify_time=NOW(3) WHERE data_date=#{dataDate} AND cons_no=#{consNo} "
            + "AND calc_version=#{calcVersion} AND deleted=0")
    int markUserDateSynced(@Param("dataDate") LocalDate dataDate,
                           @Param("consNo") String consNo,
                           @Param("calcVersion") String calcVersion,
                           @Param("syncedAt") LocalDateTime syncedAt);

    /** 按点位汇总需要修正的持久化明细，用于恢复时补建或校准任务。 */
    @Select("SELECT slot_no, COUNT(*) AS total_count, "
            + "SUM(CASE WHEN correction_status='WAITING' THEN 1 ELSE 0 END) AS waiting_count "
            + "FROM ra_user_baseline_detail WHERE data_date=#{dataDate} AND calc_version=#{calcVersion} "
            + "AND need_correction=1 AND deleted=0 GROUP BY slot_no ORDER BY slot_no")
    List<UserBaselineSlotSummary> selectCorrectionSlotSummaries(@Param("dataDate") LocalDate dataDate,
                                                                 @Param("calcVersion") String calcVersion);
}
