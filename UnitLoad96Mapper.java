package com.vpp.platform.resource.repository;

import com.baomidou.dynamic.datasource.annotation.DS;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.vpp.platform.resource.entity.UnitLoad96;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 单元级 96 点实时负荷 Mapper（TDengine 超级表 st_unit_load_96）.
 * <p>查询用 MyBatis-Plus 内置 selectList + Wraps 条件，无 XML。</p>
 */
@DS("td-resource")
@Repository
public interface UnitLoad96Mapper extends BaseMapper<UnitLoad96> {

    /**
     * 查询该单元在指定日期之前有数据的全部日期（去重）.
     * <p>用于先拿到"哪些日期有数据"，再据此挑选典型日，避免盲目拉取。</p>
     *
     * @param unitId     聚合单元编号（TAG 过滤）
     * @param beforeDate 上界日期（不含，yyyy-MM-dd）
     * @return 有数据的日期列表（data_date, yyyy-MM-dd）
     */
    @Select("SELECT DISTINCT data_date FROM st_unit_load_96 " +
            "WHERE unit_id = #{unitId} AND data_date < #{beforeDate} " +
            "AND load_mw IS NOT NULL")
    List<String> selectDistinctDataDates(@Param("unitId") String unitId,
                                         @Param("beforeDate") String beforeDate);

}
