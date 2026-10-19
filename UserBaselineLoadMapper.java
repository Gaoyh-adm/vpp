package com.vpp.platform.resource.repository;

import com.baomidou.dynamic.datasource.annotation.DS;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.vpp.platform.resource.entity.UserBaselineLoad;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

/** 用户基线TDengine映射器。 */
@DS("td-resource")
@Repository
public interface UserBaselineLoadMapper extends BaseMapper<UserBaselineLoad> {

    int ensureTable(@Param("tableName") String tableName, @Param("consNo") String consNo);

    int dropByDate(@Param("tableName") String tableName,
                   @Param("startTime") Timestamp startTime,
                   @Param("endTime") Timestamp endTime);

    List<UserBaselineLoad> selectByConsNosAndTimeRange(@Param("consNos") List<String> consNos,
                                                        @Param("startTime") Timestamp startTime,
                                                        @Param("endTime") Timestamp endTime);
}
