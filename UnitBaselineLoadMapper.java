package com.vpp.platform.resource.repository;

import com.baomidou.dynamic.datasource.annotation.DS;
import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.vpp.platform.resource.entity.UnitBaselineLoad;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

/**
 * 聚合单元基线负荷 Mapper（TDengine 超级表 st_unit_baseline_load）.
 * <p>insert：MyBatis-Plus 内置方法（PreparedStatement 支持 INSERT）。
 * delete：TDengine PreparedStatement 仅支持 SELECT/INSERT，不支持 DELETE。
 * 原生 Statement 可执行 DELETE，通过 DynamicDataSourceContextHolder 切到 td-resource 数据源。</p>
 */
@DS("td-resource")
@Repository
public interface UnitBaselineLoadMapper extends BaseMapper<UnitBaselineLoad> {

    /**
     * 按单元+运行日删除旧基线（幂等，先删后插用）.
     * <p>切到 td-resource → 原生 Statement 执行 DELETE（绕过 TDengine PreparedStatement 限制）。</p>
     */
    default void deleteByUnitAndDate(DataSource dynamicDataSource, String unitId, String dataDate) {
        DynamicDataSourceContextHolder.push("td-resource");
        try {
            Connection conn = DataSourceUtils.getConnection(dynamicDataSource);
            Statement stmt = null;
            try {
                stmt = conn.createStatement();
                stmt.execute("DELETE FROM st_unit_baseline_load WHERE unit_id = '"
                        + unitId + "' AND data_date = '" + dataDate + "'");
            } finally {
                if (stmt != null) { try { stmt.close(); } catch (Exception ignored) {} }
                DataSourceUtils.releaseConnection(conn, dynamicDataSource);
            }
        } catch (Exception e) {
            throw new RuntimeException("[基线负荷] DELETE 失败: unitId=" + unitId + ", date=" + dataDate, e);
        } finally {
            DynamicDataSourceContextHolder.poll();
        }
    }
}
