# V1 压测计量与瓶颈定位实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修正开发负载器的提交完成计量，并给出可复核的 D0 吞吐瓶颈证据。

**Architecture:** 保持既有签名 HTTP 与独立 SQL 校验；独立 READ COMMITTED / autocommit 连接周期读取支付、凭证、平衡分录和 Outbox 的同一条语句快照。按业务号匹配 HTTP 结果与提交事实，用单调时钟的上一次不可见 / 首次可见区间给出提交时间界限，禁止使用业务 created_at 代替 commit。暂不修改资金业务、连接池或并发配置。

**Tech Stack:** 现有 Java 25、JDBC / MySQL Connector/J、PowerShell；纯 Java 测试，不增加依赖。

**Spec:** `docs/quality/performance-and-resilience.md`；用户本轮授权“压测统计口径修正＋吞吐瓶颈定位”。

## Global Constraints

- 仅 D0 定位，不替代 R1 的 300 完成 TPS ×15 分钟、2000 精确查询 QPS、D1 和四小时长稳。
- 不删除既有业务数据，不输出凭据，不调整配额或背压以制造达标结果。
- HTTP 接受、提交完成、失败响应后实际持久化、未完成分别核对；空样本为 null。
- 完成延迟包含 HTTP 与异步队列等待；报告未完成等待年龄、采样耗时与误差。

## Review Focus

- 503 / 超时实际提交不能抵消另一笔 202 未完成：逐业务号匹配测试。
- 提交前时间戳、长事务、回滚不能提前记完成：真实 MySQL 事务可见性测试。
- 观察跨过窗口边界：报告确定 / 可能窗口计数，不虚构准确 commit 时间。
- 观察失败与空样本：保留错误，不能变成健康零延迟。
- 排队、容量未发与退款阶段：主阶段指标独立，未完成不消失。

## Task 1：提交观察与统计

Files: `scripts/load/LocalLoad.java`、`scripts/load/LoadMeasurements.java`、`scripts/load/LoadMeasurementsTest.java`、`scripts/load/test-local.ps1`、`scripts/load/run-local.ps1`。

- [x] 写失败测试，证明空分位数、计数抵消、窗口边界和未完成延迟错误。
- [x] 实现按请求统计和提交可见区间，独立连接采样；失败测试转绿。
- [x] 增加真实 MySQL 独立探针表事务测试，证明未提交 / 回滚不可见，旧 created_at 不影响归窗。
- [x] 运行小负载核对 JSON、逐笔结果及资金不变量，接入现有验证入口。

## Task 2：D0 阶梯负载与证据

Files: `scripts/load/diagnose-local.ps1`、`docs/quality/2026-10-19-d0-load-diagnosis.md`、`docs/releases/v1.0.0.md`、`docs/quality/performance-and-resilience.md`。

- [x] 采集现有 Prometheus 连接池 / 线程 / 任务指标，以及 MySQL 性能摘要、锁等待和执行计划；只读。
- [x] 低负载验证后，按 30 / 100 / 300 请求每秒执行短时阶梯，保留拒绝、未完成、观察开销。
- [x] 依据同窗证据定位主要限制；未能证明的猜测明确列为待验证，不贸然改资金链路。
- [x] 更新使用说明及历史证据解释，独立复核修改与统计边界。

当前目录没有 Git 仓库，不创建提交。记录测试命令、结果与来源文件摘要。

## 执行记录

- 使用 writing-plans / executing-plans、TDD、systematic-debugging、ponytail 和 doc-sync-after-change 工作流。旧空分位数测试首先出现预期断言失败，再转绿；后续区间与分类测试补齐。
- Ruling：MySQL 的 TEMPORARY 表不能在独立观察连接中读取，因此使用 UUID 命名的四张私有 InnoDB 探针表，在 finally 清理；从不向业务账表写入测试资金。
- Ruling：轮询查询已包含连接建立时间，给出保守可见区间；暂不引入 CDC 或提交时间服务，代价是观察开销和较宽区间，明确不签署 R1。
- D0 支付、查询及数据库诊断见 `docs/quality/2026-10-19-d0-load-diagnosis.md`；计量回归日志 `.work/load-measurement-final-tests.log`。新测试入口已加入 verify 和 CI 定义，远程 CI 未执行。
- 独立复核的两项 Important 已修复并定点确认；失败采样成本、成功支付凭证预期、连接关闭失败的单次计量、小样本退款比例均有处理。最终30支付+6退款、纯回归、真实MySQL及CI式无驱动测试通过。完整后端 / 前端套件不在本次无业务改动的验证范围。
