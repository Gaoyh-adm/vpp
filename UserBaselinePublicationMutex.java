package com.vpp.platform.resource.baseline;

import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.function.Supplier;

/** 使用独立MySQL连接协调各Pod的基线发布。 */
@Component
public class UserBaselinePublicationMutex {

    private static final String LOCK_SQL =
            "SELECT lock_key FROM ra_user_baseline_publish_lock "
                    + "WHERE lock_key='USER_BASELINE_PUBLICATION' FOR UPDATE";

    private final DataSource mysqlDataSource;

    public UserBaselinePublicationMutex(DataSource dataSource) {
        if (!(dataSource instanceof DynamicRoutingDataSource routing)) {
            throw new IllegalStateException("基线发布互斥需要项目现有动态数据源");
        }
        // 构造组件时没有@DS调用上下文，动态数据源会返回项目配置的默认MySQL主库。
        this.mysqlDataSource = routing.determineDataSource();
    }

    public <T> T execute(Supplier<T> action) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("基线发布入口不能处于外层事务中");
        }
        // 使用独立连接持有行锁，不绑定Spring线程事务。
        try (Connection connection = mysqlDataSource.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement(LOCK_SQL);
                     ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        throw new IllegalStateException("基线发布锁记录不存在，请先执行初始化SQL");
                    }
                }
                return action.get();
            } finally {
                try {
                    // 锁连接不修改业务数据，回滚用于结束事务并释放行锁。
                    connection.rollback();
                } finally {
                    connection.setAutoCommit(originalAutoCommit);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("获取或释放基线跨Pod发布锁失败", e);
        }
    }
}
