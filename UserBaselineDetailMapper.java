package com.vpp.platform.resource.repository;

import com.vpp.framework.db.mybatisplus.ext.SuperMapper;
import com.vpp.platform.resource.entity.UserBaselineDetail;
import org.springframework.stereotype.Repository;

/** 用户基线审计明细映射器。 */
@Repository
public interface UserBaselineDetailMapper extends SuperMapper<UserBaselineDetail> {
}
