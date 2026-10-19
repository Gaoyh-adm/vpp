package com.vpp.platform.resource.repository;

import com.vpp.framework.db.mybatisplus.ext.SuperMapper;
import com.vpp.platform.resource.entity.UserBaselineCorrectionTask;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.springframework.stereotype.Repository;

/** 用户基线修正任务映射器。 */
@Repository
public interface UserBaselineCorrectionTaskMapper extends SuperMapper<UserBaselineCorrectionTask> {

    /** 以状态条件领取到期修正任务。 */
    @Update("UPDATE ra_user_baseline_correction_task SET status='PROCESSING', last_modify_time=NOW(3) "
            + "WHERE id=#{id} AND status IN ('PENDING','RETRY_WAIT') AND deleted=0")
    int claimForProcessing(@Param("id") Long id);
}
