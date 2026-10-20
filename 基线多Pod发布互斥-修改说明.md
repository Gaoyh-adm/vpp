# 基线多 Pod 发布互斥：最简修改说明

依据：`217079f`。本文仅提供修改方案，未修改 Java、XML 或数据库。

## 1. 结论与改动范围

保留多 Pod 部署及现有任务租约，使用现有 MySQL 增加一条专用锁记录。所有 Pod 在发布前锁住同一条记录，结束后释放。原有 `ReentrantLock` 可以保留，用于串行本 Pod 内的调用；真正的跨 Pod 互斥由 MySQL 行锁完成。

锁只覆盖当前发布器内的短流程，典型日选择、数据拟合、异常剔除、窗口读取和 K 计算仍在锁外。

| 位置 | 修改 |
| --- | --- |
| 新增 MySQL 锁表及固定记录 | 执行第 2 节 SQL |
| 应用配置 | 指定现有动态数据源中实际的 MySQL 写库名称 |
| 新增 `UserBaselinePublicationMutex.java` | 独立 MySQL 连接持有行锁 |
| `UserBaselinePublisher.java` 第 28 行附近 | 注入新增互斥组件 |
| 同文件 `publish`、`publishCorrections` | 本地锁内再取得跨 Pod 锁，替换代码见第 5 节 |

其他生成、修正服务以及 Mapper 保持当前调用方式。无需 Redis、额外任务租约字段或逐户逐点加锁。

## 2. MySQL：新增一张单行锁表

在所有 Pod 共用的基线 MySQL 写库执行：

```sql
CREATE TABLE IF NOT EXISTS ra_user_baseline_publish_lock (
    lock_key VARCHAR(64) NOT NULL,
    PRIMARY KEY (lock_key)
) ENGINE=InnoDB;

INSERT IGNORE INTO ra_user_baseline_publish_lock (lock_key)
VALUES ('USER_BASELINE_PUBLICATION');
```

这条记录长期保留，不按天删除。所有日期、户号和算法版本共用同一个锁键，因为当前 TDengine 的同户号、同时间戳写入没有按算法版本隔离。

## 3. 配置：明确选择现有 MySQL 写库

仓库已经使用 `dynamic-datasource`，见 `UserBaselineLoadMapper.java` 的 `@DS("td-resource")`。新增配置：

```yaml
vpp:
  resource:
    user-baseline:
      mysql-data-source-name: ${BASELINE_MYSQL_DATASOURCE_NAME}
```

`BASELINE_MYSQL_DATASOURCE_NAME` 填完整项目现有 `spring.datasource.dynamic.datasource` 下的 MySQL 写库键名。本仓库未包含该配置，不能直接假定名称为 `master`。各 Pod 必须连接同一个 MySQL 写库并使用同一条锁记录，不能使用读库或各自独立的数据库。

此配置只选择已有数据源，不新建连接池。

## 4. 新增 UserBaselinePublicationMutex.java

在 `com.vpp.platform.resource.baseline` 包下新增：

```java
package com.vpp.platform.resource.baseline;

import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.function.Supplier;

/** 使用独立 MySQL 连接协调各 Pod 的基线发布。 */
@Component
public class UserBaselinePublicationMutex {

    private static final String LOCK_SQL =
            "SELECT lock_key FROM ra_user_baseline_publish_lock "
                    + "WHERE lock_key='USER_BASELINE_PUBLICATION' FOR UPDATE";

    private final DataSource mysqlDataSource;

    public UserBaselinePublicationMutex(
            DataSource dataSource,
            @Value("${vpp.resource.user-baseline.mysql-data-source-name}")
            String mysqlDataSourceName) {
        if (!(dataSource instanceof DynamicRoutingDataSource routing)) {
            throw new IllegalStateException("基线发布互斥需要项目现有动态数据源");
        }
        // 直接选择实际 MySQL 数据源，避免受到当前线程 @DS 路由影响。
        DataSource selected = routing.getDataSources().get(mysqlDataSourceName);
        if (selected == null) {
            throw new IllegalStateException(
                    "基线发布锁的 MySQL 数据源不存在：" + mysqlDataSourceName);
        }
        this.mysqlDataSource = selected;
    }

    public <T> T execute(Supplier<T> action) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("基线发布入口不能处于外层事务中");
        }
        // 直接获取独立连接，不使用 DataSourceUtils，不绑定 Spring 线程事务。
        try (Connection connection = mysqlDataSource.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement(LOCK_SQL);
                     ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        throw new IllegalStateException("基线发布锁记录不存在，请先执行初始化 SQL");
                    }
                }
                // 关闭查询并不会释放行锁；锁持续到下面的 rollback。
                return action.get();
            } finally {
                try {
                    // 锁连接不修改业务数据，回滚只用于结束事务、释放行锁。
                    connection.rollback();
                } finally {
                    connection.setAutoCommit(originalAutoCommit);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("获取或释放基线跨 Pod 发布锁失败", e);
        }
    }
}
```

锁表记录已经存在，因此其他 Pod 的同一条 `SELECT ... FOR UPDATE` 会等待当前锁释放。锁等待超时、锁记录缺失等情况直接抛异常，交给现有失败处理和重试；不能绕过锁继续执行，也不能返回 0 冒充成功。

**独立连接是本方案的关键。** 不要把上述锁查询改成 Mapper 加 `@Transactional` 包住整个发布器：这会改变原有业务事务提交时点，并影响 TDengine 的动态数据源切换。新增组件本身也不加 `@Transactional`。

## 5. 修改 UserBaselinePublisher.java

### 5.1 新增依赖

在现有 `persistenceService` 字段后增加：

```java
private final UserBaselinePublicationMutex publicationMutex;
```

现有 `@RequiredArgsConstructor` 会负责构造器注入。保留 `ReentrantLock publicationLock` 字段。

### 5.2 完整替换 publish 方法（原第 33—40 行）

```java
public int publish(List<Long> detailIds) {
    if (detailIds == null || detailIds.isEmpty()) {
        return 0;
    }
    publicationLock.lock();
    try {
        return publicationMutex.execute(() -> publishLocked(detailIds));
    } finally {
        publicationLock.unlock();
    }
}
```

### 5.3 完整替换 publishCorrections 方法（原第 43—60 行）

```java
public int publishCorrections(Long taskId, String owner,
                              List<UserBaselineDetail> calculated) {
    if (calculated == null || calculated.isEmpty()) {
        return 0;
    }
    publicationLock.lock();
    try {
        return publicationMutex.execute(() -> {
            List<Long> ids = calculated.stream()
                    .map(UserBaselineDetail::getId).toList();
            // 仍先保证原始 B0 已经发布。
            publishLocked(ids);
            // 业务 MySQL 事务独立提交；此时专用锁连接仍然持锁。
            List<UserBaselineDetail> saved = persistenceService.saveCorrections(
                    taskId, owner, calculated);
            // 保存后重新读取当前结果，并发布修正值。
            return publishLocked(saved.stream()
                    .map(UserBaselineDetail::getId).toList());
        });
    } finally {
        publicationLock.unlock();
    }
}
```

### 5.4 保留原有其他实现

`publishLocked` 第 63—89 行、`toLoad`、`isFinalStatus` 保持当前逻辑。可把类注释改成“本地锁和 MySQL 专用行锁共同协调原始基线及修正结果发布”，避免继续标为仅单实例。

继续保留以下条件：

- 在取得两把锁之后才读取 MySQL，不能提前读出整行结果再传入发布器。
- `saveCorrections` 仍是独立 Spring Bean 中的事务，返回前已经提交。
- `publish`、`publishCorrections` 及其调用方不增加外层事务。
- MySQL 的 `PENDING/SYNCED`、按版本和状态确认、仅更新 `WAITING` 明细的检查全部保留。
- 正式生成、修正及补写都经过上述两个发布入口。仓库内尚未被这些流程调用的 `LoadStore.upsertPoint/replaceUserDate` 不能作为绕过互斥的旁路。

## 6. 为什么能挡住本次覆盖问题

取得全局行锁后，执行范围分别是：

```text
普通发布：
取得锁 → 重新读取 MySQL → 写 TDengine → 确认 SYNCED → 释放锁

修正发布：
取得锁 → 确认 B0 已发布 → 保存修正并提交 → 重新读取并发布修正值
       → 确认 SYNCED → 释放锁
```

Pod A 发布 B0 时，Pod B 不能进入修正保存及发布。Pod B 先完成修正时，Pod A 后续取得锁，读取到的是当前修正结果；若已经 `SYNCED` 则跳过，不再持有旧 B0 去覆盖。

任务 owner、租约解决谁能计算和保存同一个任务；新增行锁解决不同任务之间的发布交错，两者继续各司其职。

这仍是两个数据库之间的顺序写入，不是跨库原子事务。TDengine 写入失败，或写成功但 MySQL 确认失败时，继续使用现有 `PENDING` 补写机制，不重新计算已保存的修正值。

## 7. 上线及验收

先执行锁表 SQL、配置实际 MySQL 写库名称，再替换上述代码。MySQL 连接池要同时容纳专用锁连接、业务保存和心跳；不能将连接池最大连接数设为 1。由于保留本地锁，每个 Pod 同时最多有一个发布流程占用专用锁连接。

暂停基线生成与修正调度，等待已有执行结束，全部 Pod 更新完成后再恢复调度。旧版不获取新增行锁，不能让新旧版本同时发布同一批数据。无需停止其他业务功能。

至少验收以下场景：

1. **跨 Pod 同时发布和修正：** A 取得锁并读出 B0 后暂停，B 应等待且不能保存修正；恢复 A 后 B 完成修正，TDengine 最后为修正值。
2. **修正先完成：** B 完成修正并确认后，A 补写相同 ID，应跳过或发布当前修正值，不能回退为 B0。
3. **修正值写失败：** MySQL 保持已保存修正值及 `PENDING`，任意 Pod 后续补写当前值。
4. **B0 写失败：** 本次不能继续保存修正，后续仍先完成 B0 发布。
5. **失败释放与等待超时：** 正常异常退出后其他 Pod 可取得锁；等待超时应进入原有异常处理，不能无锁写入。
6. **多数据源：** 实际查询锁表及 `saveCorrections` 使用 MySQL 写库，`LoadStore` 使用 TDengine，不能因外层事务串错数据源。

本文新增组件和两个替换方法已从文档提取并通过 Java 21 编译，使用两个独立发布器及独立互斥组件进行了 8 项隔离检查：共享锁等待、后续补写、异常释放、锁记录缺失、外层事务拒绝、错误数据源名称、B0 写失败和修正写失败后重放，全部通过。

验证使用 JDBC、数据库及路由替代实现，没有运行真实 MySQL 行锁、Spring 容器或 TDengine。真实环境仍需在完整项目的两个 Pod 上按上述步骤联调后上线。
