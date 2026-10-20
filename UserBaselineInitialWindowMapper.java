package com.vpp.platform.resource.repository;

import com.vpp.framework.db.mybatisplus.ext.SuperMapper;
import com.vpp.platform.resource.entity.UserBaselineInitialWindow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

/** 用户首次基线初始化窗口映射器。 */
@Repository
public interface UserBaselineInitialWindowMapper extends SuperMapper<UserBaselineInitialWindow> {

    @Insert("INSERT INTO ra_user_baseline_initial_window(cons_no,calc_version,first_target_time,"
            + "seed_window_json,initialized_at,remark,deleted) VALUES(#{row.consNo},#{row.calcVersion},"
            + "#{row.firstTargetTime},#{row.seedWindowJson},#{row.initializedAt},#{row.remark},0) "
            + "ON DUPLICATE KEY UPDATE id=id")
    int insertIfAbsent(@Param("row") UserBaselineInitialWindow row);
}
