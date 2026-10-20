package com.vpp.platform.resource.repository;

import com.vpp.framework.db.mybatisplus.ext.SuperMapper;
import com.vpp.platform.resource.entity.UserBaselineCorrectionTask;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/** 用户基线修正任务映射器。 */
@Repository
public interface UserBaselineCorrectionTaskMapper extends SuperMapper<UserBaselineCorrectionTask> {

    /** 查询待执行任务以及租约已经过期的处理中任务。 */
    @Select("SELECT * FROM ra_user_baseline_correction_task WHERE deleted=0 "
            + "AND scheduled_time<=#{now} AND (status IN ('PENDING','RETRY_WAIT') "
            + "OR (status='PROCESSING' AND (lease_expire_time IS NULL OR lease_expire_time<=#{now}))) "
            + "ORDER BY scheduled_time,id LIMIT #{limit}")
    List<UserBaselineCorrectionTask> selectDueRecoverable(@Param("now") LocalDateTime now,
                                                           @Param("limit") int limit);

    /** 原子领取普通待办或租约已经过期的任务。 */
    @Update("UPDATE ra_user_baseline_correction_task SET status='PROCESSING', execution_owner=#{owner}, "
            + "lease_expire_time=#{leaseExpireTime}, heartbeat_time=#{now}, last_modify_time=NOW(3) "
            + "WHERE id=#{id} AND deleted=0 AND scheduled_time<=#{now} "
            + "AND (status IN ('PENDING','RETRY_WAIT') OR (status='PROCESSING' "
            + "AND (lease_expire_time IS NULL OR lease_expire_time<=#{now})))")
    int claimForProcessing(@Param("id") Long id,
                           @Param("owner") String owner,
                           @Param("now") LocalDateTime now,
                           @Param("leaseExpireTime") LocalDateTime leaseExpireTime);

    /** 仅允许当前执行者续租。 */
    @Update("UPDATE ra_user_baseline_correction_task SET lease_expire_time=#{leaseExpireTime}, "
            + "heartbeat_time=#{now}, last_modify_time=NOW(3) WHERE id=#{id} AND deleted=0 "
            + "AND status='PROCESSING' AND execution_owner=#{owner}")
    int renewLease(@Param("id") Long id,
                   @Param("owner") String owner,
                   @Param("now") LocalDateTime now,
                   @Param("leaseExpireTime") LocalDateTime leaseExpireTime);

    /** 仅允许当前执行者完成任务。 */
    @Update("UPDATE ra_user_baseline_correction_task SET status='SUCCESS', success_count=#{successCount}, "
            + "failed_count=#{failedCount}, completed_at=#{completedAt}, last_error=NULL, "
            + "lease_expire_time=NULL, heartbeat_time=#{completedAt}, last_modify_time=NOW(3) "
            + "WHERE id=#{id} AND deleted=0 AND status='PROCESSING' AND execution_owner=#{owner}")
    int complete(@Param("id") Long id,
                 @Param("owner") String owner,
                 @Param("successCount") int successCount,
                 @Param("failedCount") int failedCount,
                 @Param("completedAt") LocalDateTime completedAt);

    /** 当前执行者发生系统异常时释放任务，供后续重试。 */
    @Update("UPDATE ra_user_baseline_correction_task SET status='RETRY_WAIT', retry_count=retry_count+1, "
            + "last_error=#{lastError}, execution_owner=NULL, lease_expire_time=NULL, "
            + "last_modify_time=NOW(3) WHERE id=#{id} AND deleted=0 "
            + "AND status='PROCESSING' AND execution_owner=#{owner}")
    int releaseForRetry(@Param("id") Long id,
                        @Param("owner") String owner,
                        @Param("lastError") String lastError);

    /** 校准任务总数，不改动执行状态和租约。 */
    @Update("UPDATE ra_user_baseline_correction_task SET total_count=#{totalCount}, "
            + "last_modify_time=NOW(3) WHERE id=#{id} AND deleted=0")
    int updateTotalCount(@Param("id") Long id, @Param("totalCount") int totalCount);

    /** 成功任务出现新增待修正明细时重新打开，并清空上一轮执行信息。 */
    @Update("UPDATE ra_user_baseline_correction_task SET status='PENDING', total_count=#{totalCount}, "
            + "success_count=0, failed_count=0, completed_at=NULL, last_error=NULL, execution_owner=NULL, "
            + "lease_expire_time=NULL, heartbeat_time=NULL, last_modify_time=NOW(3) "
            + "WHERE id=#{id} AND deleted=0 AND status='SUCCESS'")
    int reopenCompleted(@Param("id") Long id, @Param("totalCount") int totalCount);
}
