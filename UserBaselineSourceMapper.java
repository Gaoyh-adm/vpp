package com.vpp.platform.resource.repository;

import com.baomidou.dynamic.datasource.annotation.DS;
import com.vpp.platform.resource.baseline.model.UserLoadPoint;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

/** 用户基线负荷数据只读映射器。 */
@DS("td-resource")
@Repository
public interface UserBaselineSourceMapper {

    List<UserLoadPoint> selectByConsNosAndDates(@Param("consNos") List<String> consNos,
                                                 @Param("dataDates") List<String> dataDates);

    List<UserLoadPoint> selectByConsNosAndTimeRange(@Param("consNos") List<String> consNos,
                                                     @Param("startTime") Timestamp startTime,
                                                     @Param("endTime") Timestamp endTime);
}
