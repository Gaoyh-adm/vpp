package com.vpp.platform.resource.repository;

import com.vpp.framework.db.mybatisplus.ext.SuperMapper;
import com.vpp.platform.resource.entity.UserBaselineInitialWindow;
import org.springframework.stereotype.Repository;

/** 用户首次基线初始化窗口映射器。 */
@Repository
public interface UserBaselineInitialWindowMapper extends SuperMapper<UserBaselineInitialWindow> {
}
