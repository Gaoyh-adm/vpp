# 基线并发修复最简实施方案

适用代码：2cdca39。本文是修改方案，尚未修改Java代码，尚未做完整服务联调。

采用一条发布原则：MySQL保留计算全过程，TDengine只发布定稿结果。同一个户号、业务日期、点序号、算法版本对应的定稿结果保存后不可修改，补写始终发送同一份结果。

这是对当前发布行为的明确调整：WAITING点的原始基线仍保留在MySQL，但不再提前写入TDengine；需要展示待修正原始基线的查询从MySQL读取。如果必须保留TDengine中的WAITING占位结果，本方案不能原样采用。

## 1. 明确允许发布的状态

继续使用现有状态，不新增业务计算状态：

| correction_status | 是否允许写TDengine |
|---|---|
| WAITING | 否，只保存在MySQL，等待实时修正 |
| NOT_REQUIRED | 是，原始基线已经是最终结果 |
| NO_BASELINE | 是，发布该点无基线的最终状态 |
| CORRECTED | 是，发布修正结果 |
| UNCORRECTABLE | 是，发布保留B0和无法修正原因的最终结果 |

sync_status仍使用PENDING、SYNCED。WAITING/PENDING是正常等待状态，不应仅因它未同步就判定每日原始基线生成失败。

## 2. 统一发布，只保留一个出口

增加或提取UserBaselinePublisher，由生成服务和修正服务共同调用。两个服务不再直接调用TDengine写入方法。

发布流程：

1. 按明细id读取MySQL，过滤为上表四种定稿状态且sync_status=PENDING的记录。
2. 将保存的完整结果转换为TDengine行；数值、状态、原因、generatedAt、correctedAt等均使用已存值，重试时不重新生成业务内容。
3. 确保对应户号子表存在，再调用upsertPoints。取消生成服务中的replaceUserDate调用，避免重试删除全天数据。
4. 写入成功后，只确认本次发送的具体明细，不更新计算字段，不确认整户全天。

确认SQL：

```sql
UPDATE ra_user_baseline_detail
SET sync_status = 'SYNCED',
    synced_at = NOW(3),
    last_modify_time = NOW(3)
WHERE id = #{id}
  AND calc_version = #{calcVersion}
  AND deleted = 0
  AND correction_status = #{sentStatus}
  AND correction_status IN
      ('NOT_REQUIRED', 'NO_BASELINE', 'CORRECTED', 'UNCORRECTABLE')
  AND sync_status = 'PENDING';
```

影响0行时重新读取：若同一明细已为相同定稿状态且SYNCED，按重复确认处理；其他情况报错，不能无条件视为成功。

发布允许重试或重复执行，因为同一点定稿后全部业务字段固定，所有执行者发送相同结果。此处不依靠TDengine支持条件更新或与MySQL组成分布式事务。

## 3. 每日生成和修正任务共用同样的执行权规则

为ra_user_baseline_run补充与修正任务一致的执行字段：execution_owner、lease_expire_time、heartbeat_time。字段类型参照现有修正任务对应字段，实际迁移须按真实数据库结构编写。

每次领取在方法内创建UUID；不要把executionOwner保存在Service实例字段中。将owner作为本次执行上下文传给续租、保存、完成及异常释放方法。

所有租约判断、续期使用MySQL时间，避免不同服务器时钟影响领取。租约时长沿用现有配置，独立心跳按租约时长的一部分周期续租；不能只在整个耗时批次结束后续租。续租失败后停止继续处理，写入处仍需下面的事务校验。

每日生成领取SQL示意：

```sql
UPDATE ra_user_baseline_run
SET status = 'RUNNING',
    execution_owner = #{owner},
    lease_expire_time = TIMESTAMPADD(SECOND, #{leaseSeconds}, NOW(3)),
    heartbeat_time = NOW(3),
    last_modify_time = NOW(3)
WHERE id = #{id}
  AND deleted = 0
  AND (
    status IN ('FAILED', 'PARTIAL_FAILED')
    OR (status = 'RUNNING' AND lease_expire_time <= NOW(3))
  );
```

只有影响1行才能恢复。首次创建RUNNING记录时同时写入owner、租约、心跳；通过有效业务日期+版本唯一约束拦住重复创建。已有旧版RUNNING记录的空租约，应在停用旧执行者后作为迁移事项处理，不能在旧版仍运行时直接放开领取。

修正任务沿用现有原子领取SQL，改为每次领取独立owner，并统一数据库时钟。完成和异常释放同样要求对应运行状态及owner，正常完成还需有效租约；旧执行者不允许修改新执行者的运行记录。

## 4. 在MySQL短事务内保存结果

计算和TDengine写入均放在该事务之外。使用单独Spring Bean或TransactionTemplate明确开启MySQL事务，避免同类自调用@Transactional失效；不要把TDengine调用放进该MySQL事务。

每批保存的步骤：

```text
事务开始
  SELECT 对应run/task FOR UPDATE
  使用数据库当前时间验证：运行状态、owner、租约均有效
  不符合：终止保存

  每日生成：仅插入不存在的明细，不更新已有明细
  修正保存：仅更新仍为WAITING的明细
             -> CORRECTED或UNCORRECTABLE，并置sync_status=PENDING
事务提交

调用统一发布出口，读取已经保存的定稿结果发布
```

修正保存需要列出计算结果字段进行条件更新，替换普通updateById：

```sql
UPDATE ra_user_baseline_detail
SET actual_window_json = #{actualWindowJson},
    baseline_window_json = #{baselineWindowJson},
    actual_window_mean_mw = #{actualWindowMeanMw},
    baseline_window_mean_mw = #{baselineWindowMeanMw},
    k_value = #{kValue},
    final_baseline_mw = #{finalBaselineMw},
    correction_status = #{finalStatus},
    calculation_remark = #{calculationRemark},
    corrected_at = #{correctedAt},
    sync_status = 'PENDING',
    synced_at = NULL,
    last_modify_time = NOW(3)
WHERE id = #{id}
  AND calc_version = #{calcVersion}
  AND correction_status = 'WAITING'
  AND deleted = 0;
```

SQL必须在上述已经锁定并校验task的同一个MySQL事务内执行。影响0行时读取现有明细：已经定稿则复用，不得重新覆盖；其他异常终止处理。所有定稿计算字段此后均禁止更新，同步确认只更新同步字段。

初始化种子的首次保存也要受有效执行权保护；允许事务外计算种子，但写入应在校验task的MySQL事务内完成。保留每户每版本仅初始化一次的约束。

## 5. 补上前4小时依赖检查

取消TDengine中的WAITING占位后，不能继续只依靠buildBaselineWindow里的TDengine状态判断前一点是否完成。

修正当前用户当前点之前，先查询该用户、相同版本、前4小时内已有MySQL明细：

- 存在WAITING，或任何尚未同步的定稿结果：本点等待重试，不计算K，不标记无法修正。
- 相关历史点都已发布后，再读取TDengine最终结果组成窗口。
- 原本就没有历史明细的点，继续按已确认的一次性初始化、前值补全规则处理；不要因没有明细而永久等待。

检查区间包含targetTime-4h，不包含targetTime。跨日按完整时间查询。MySQL中NO_BASELINE且已同步的历史点仍按既定缺值规则处理，不额外改动算法。

当前点依赖未就绪时保留WAITING，任务保持可重试；其他已就绪用户可继续处理。waiting数量不为0时不能将任务标记SUCCESS。

## 6. 恢复和唯一约束

有效记录至少保证以下逻辑唯一性，落地迁移前核对已有重复数据及逻辑删除方式：

- 每日运行：data_date、calc_version。
- 用户明细：cons_no、data_date、slot_no、calc_version。
- 修正任务：data_date、slot_no、calc_version。
- 初始化窗口：cons_no、calc_version。

每日生成恢复：已存在明细全部复用，只计算不存在的点；定稿且未同步的明细走统一发布出口。WAITING明细交给修正任务，不作为生成补写对象。

修正任务恢复：先发布已定稿的待同步明细，再计算仍为WAITING且依赖已就绪的点。即便旧执行者在网络暂停后继续发布，它只能读取并发送同一份冻结结果，不能发送另一份计算值或早期WAITING值。

## 文件修改清单

| 文件 | 修改内容 |
|---|---|
| UserBaselineRun及Mapper | 执行字段、原子领取、续租、带owner完成/释放、事务锁定读取 |
| UserBaselineCorrectionTaskMapper | 每次领取独立owner，数据库时钟，事务锁定读取 |
| UserBaselineGenerationService | 允许失效RUNNING恢复，仅插入缺少明细，删除直接TDengine发布和整日确认调用 |
| UserBaselineCorrectionService | 计算与保存分离，前4小时MySQL依赖检查，调用受保护保存和统一发布 |
| UserBaselineInitialWindowService | 种子计算与首次持久化分离，持久化受当前执行权保护 |
| UserBaselineDetailMapper | WAITING条件保存、按id确认同步、历史依赖查询 |
| 新增结果保存事务组件 | 在同一MySQL事务中锁定run/task、验证执行权并保存明细/种子 |
| 新增UserBaselinePublisher | 只发布定稿结果，失败重试，按具体明细确认 |
| 数据库迁移及查询调用方 | 执行字段、唯一约束；需要WAITING原始值的查询改读MySQL |

## 必须通过的验证

1. 每日生成中途停止：租约失效后可恢复，已有点数值保持不变，不删除TDengine全天数据。
2. A暂停到租约失效，B接管后完成，A再恢复：A不能保存另一份结果，也不能修改B的任务状态。
3. 生成恢复和实时修正同时执行：生成服务不发布WAITING，定稿结果不被原始值覆盖。
4. TDengine写成功但MySQL确认失败：重试发送相同完整结果，最终确认成功。
5. 当前点前4小时有WAITING或待发布结果：当前点等待，历史发布完成后再计算K。
6. 多实例同时创建/领取任务和初始化窗口：有效记录无重复，只有持有当前执行权的计算结果可保存。
7. 已定稿点出现迟到负荷：已存结果不变，只影响后续未计算点。

上述检查须包含真实MySQL事务与TDengine补写联调。本文没有承诺故障下两库同时提交；依靠定稿结果不可变和重复发布相同内容，使失败后能够恢复一致。
