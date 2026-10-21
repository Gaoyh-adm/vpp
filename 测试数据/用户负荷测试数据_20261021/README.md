> 下载并解压同目录的[SQL 数据包](用户负荷测试数据_20261021.zip)，再按照下文执行。文中的文件路径均以解压后的数据目录为根目录。包内包含完整 SQL、生成器、户号名单和隔离验证代码。

# 近30天用户负荷测试 SQL

目标业务日：2026-10-21；数据日期：2026-09-22—2026-10-21，共30天。
输入名单：959个不重复户号，全部保留，未擅自筛为893户。
共2,761,920条物理负荷记录，其中非空2,761,496条、空负荷424条。空负荷通过load_mw=NULL构造，程序查询IS NOT NULL后等同缺点；不使用0伪装缺点。

## 执行顺序

1. 本包SQL直接使用现有TDengine数据库`vpp_resource`，不创建新库。先执行`00_TDengine_建表.sql`：按已确认结构创建源超级表（已存在则保留）、结果超级表及28,770个按“户号＋业务日期”划分的负荷子表。cons_no NCHAR(40)、data_date NCHAR(10)均为TAG；普通列为ts TIMESTAMP、load_mw DOUBLE、src_type NCHAR(20)。建表使用IF NOT EXISTS，保留已有表结构；不DROP/DELETE实际负荷。已有超级表的列与TAG须和脚本DDL一致，IF NOT EXISTS不会自动修改已有表结构。
2. 按日期执行`load/`内全部30份SQL，文件均自带USE。每户每天一张固定日期子表、一条包含96组值的单行INSERT；INSERT只写ts、load_mw、src_type，户号和日期从子表TAG读取。每条完整SQL占一行以兼容taos -f，VALUES使用TDengine格式，不使用MySQL存储过程或递归CTE。
3. 程序继续使用现有`td-resource`数据源连接`vpp_resource`，无需切换数据库配置；源负荷和基线结果均在此库。MySQL仍需有名单中的有效用户及日历。
4. 执行`01_MySQL_前置检查.sql`：确认20个重点户号有效、主验证日前5个工作日为2026-10-20, 2026-10-19, 2026-10-16, 2026-10-15, 2026-10-14，且所属聚合单元没有影响这些日期的参与标记。该SQL不修改用户、日历或参与记录。无效户号不会被基线任务处理，959个导入户号不一定等于893个有效户号。
5. 执行`02_TDengine_导入核验.sql`；核对记录数量及缺点。
6. 运行`generateDailyUserBaseline`，日期参数填`2026-10-21`或当天留空。确认本轮确实全量重算。再执行分钟修正任务，直到这些已到期重点点位处理完成。未来点到时才处理，本包当日全96点为模拟数据。
7. 执行`03_MySQL_计算结果核验.sql`，对照`预期结果.csv`、`expected_cases.json`。
8. 如需核对公休日，参数填`2026-10-18`另跑一遍，核对S01-REST的20MW及样本日期均为RESTDAY。实际日历可能把国庆、中秋、调休日期排除，所以公休日样本数量不写死为5。

## 边界和前提

- 15分钟点为00:15、00:30……次日00:00；最后一个点所在子表的data_date TAG仍为前一天，导入与程序使用同一业务时区Asia/Shanghai。
- 不生成法定节假日分支的专门期望值，不修改已有日历。不把10月1日等日期擅自改成普通日。默认负荷按星期构造曲线；代码仍按MySQL实际日历选典型日。
- 固定数值期望只在预期工作日、参与状态匹配时成立。历史标记不同而样本换天，不可直接判程序错误；先看sample_detail_json。
- 先找真实值、不足再拟合，因此S02只有缺点不会触发拟合；S03/S04刻意让真实值不足5天。
- S01额外在00:15需要修正。若库内没有昨日基线，走跨日初始化；有昨日基线时直接使用昨日最终值，预期都是K=1、最终10。
- S09的10:30预期使用10:15修正后12：历史窗口均值=(15×10+12)/16=10.125，K=1.185185，最终11.851851。先完成10:15再核对10:30。
- S13的07:30缺点在07:15=8、07:45=16之间取前值8，实际窗口均值11.75；S14的06:15前面4小时均无值，取06:30=14，均值12.25。
- S15查询能使用的实际值全部为空；未来10:30之后有值也不能用来修正10:15，结果为UNCORRECTABLE并保留B0=10。
- S16只在计算K窗口时用前值补基线；07:30原来的NO_BASELINE输出本身不被篡改。
- S12/S15无法修正时，当前代码保存的两个窗口均值字段均为NULL；CSV中的actual_mean/baseline_mean仅解释数学条件，不能要求这些列也写入表。K均为空，最终保留10。
- 未自动构造“参与日补足”，因为参与属于聚合单元，不能按户号乱标记。未声称仅30天数据可完全验证30工作日/20公休日搜索上限；这需要额外更早的边界样本。
- 本包只生成SQL，不连接数据库；没有执行实际插入、覆盖或应用配置修改。重跑相同SQL是同时间戳覆盖，缺点保持NULL的行为需要当前TDengine支持明确NULL覆盖。本包按当前空负荷表准备，重复导入时需核对NULL覆盖行为。

## 子表结构和导入示例

用户已确认`st_user_load_96`有两个TAG，按cons_no、data_date顺序。因此同一子表不能跨业务日期复用：959户×30天=28,770张负荷子表。全部子表DDL在`00_TDengine_建表.sql`中。

```sql
USE vpp_resource;
CREATE TABLE IF NOT EXISTS fixture_load_f94b99aa657f444f9c3b7892_20261021 USING st_user_load_96 TAGS ('SD220-001', '2026-10-21');
INSERT INTO fixture_load_f94b99aa657f444f9c3b7892_20261021 (ts, load_mw, src_type) VALUES ('2026-10-21 00:15:00.000', 2, 'DEVICE');
```

原包在创建负荷子表时即报TAG数量不匹配。请用新版完整解压后的文件重新执行；不需要删除或修改超级表。原包的普通列data_date插入方式已去掉，缺点与异常值数据保持原样。

## 文件说明

- `accounts.txt`：完整原始959户名单。
- `load/*.sql`：按天拆分的可执行TDengine负荷数据。
- `预期结果.csv`/`expected_cases.json`：20个重点户号、23个主要检查点。
- `validation/case_loads.csv`：重点户号完整输入，用于隔离验证。
- `generate_fixture.py`：仅用Python标准库的生成器，可改目标日期、天数、库名重新生成；生成器依赖仓库的结果表DDL。
- `验证报告.txt`：生成后补充的实际检查记录；真实TDengine、MySQL、Spring联调需在你的环境执行。

## 本包已完成的检查

已逐行检查2,761,920条SQL数据的户号、点数、时间戳和业务日期；23个重点检查点使用仓库选样、剔除、修正及窗口构建代码隔离验证通过。详见`验证报告.txt`。

如有JDK 21，可进入`validation/`目录重跑隔离检查：

```sh
javac -encoding UTF-8 FixtureCheck.java
java FixtureCheck case_loads.csv expected.tsv
```

这不替代实际数据库联调。固定期望值要求MySQL用户有效、日历和参与状态符合前置说明。

使用taos客户端批量导入的示例（在解压后的根目录运行，连接参数按自己的环境补充）：

```sh
taos -f 00_TDengine_建表.sql
for file in load/*.sql; do
  taos -f "$file" || break
done
```

这些命令仅执行TDengine文件；MySQL检查文件应使用MySQL客户端执行。

## 服务器一次导入

本包已将每条完整SQL写在同一行，适配taos -f。旧包的分行VALUES在本次客户端中会被逐行当成独立语句，这是格式问题。

```sh
cat 00_TDengine_建表.sql load/*.sql > testAll.sql
taos -u root -p -f "$PWD/testAll.sql"
```

若服务器已经上传了旧版/root/testAll.sql，可在Linux Shell直接修复格式，不必重新上传数据：

```sh
awk '/^[[:space:]]*--/ {next} {sub(/\r$/, ""); printf "%s ", $0; if ($0 ~ /;[[:space:]]*$/) printf "\n"}' /root/testAll.sql > /root/testAll_fixed.sql
taos -u root -p -f /root/testAll_fixed.sql
```

该awk命令仅针对本包生成的SQL（语句末行以分号结束、无字符串内跨行），保留原文件并生成新文件。无须删除已建好的表。
