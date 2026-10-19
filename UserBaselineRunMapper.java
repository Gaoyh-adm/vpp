package com.vpp.platform.resource.repository;

import com.vpp.framework.db.mybatisplus.ext.SuperMapper;
import com.vpp.platform.resource.entity.UserBaselineRun;
import org.springframework.stereotype.Repository;

/** 用户基线运行记录映射器。 */
@Repository
public interface UserBaselineRunMapper extends SuperMapper<UserBaselineRun> {
}
