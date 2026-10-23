# 第二轮审查修复 Implementation Plan

> **For agentic workers:** 使用 superpowers:executing-plans 在当前会话逐项执行，先观察回归失败，再实现并验证；完成后做一次独立审查。

**Goal:** 修复第二轮审查四项 P2，保留资金约束和现有 V1 范围。

**Architecture:** 在支付 / 退款更新渠道流水号的位置归一化唯一键冲突，沿用既有独立事务留证；确定性证据冲突任务立即隔离。重复账单只需判断另一条相同业务记录是否存在，使用有界 EXISTS 而不维护新的聚合状态。详情按完整凭证及各类记录分别游标分页，编码错误进入原有隔离流程。

**Tech Stack:** Java 25、Spring Boot 4.1.1 / JDBC、MySQL 8.4、React 19 / TanStack Query / Ant Design 6；不新增依赖。

**Spec:** `docs/reviews/2026-10-18-code-review-round-2.md`，及现有 V1 需求与运行约定。

## Global Constraints

- 保留支付 / 退款唯一约束、根支付锁、状态 / 账务 / Outbox 原子性；未知资金不得自动释放。
- 原始证据保留；本地 admin/admin、版本 1.0.0-SNAPSHOT 和现有性能发布门槛保持。
- 当前目录没有 Git 元数据；在已授权的共享目录执行，使用 `.work/round2-fixes-before` 保存修改前文件；不虚构提交或工作树。
- 用户已授权修复这四项；计划用于记录执行，不增加审批停顿。

## Review Focus

- 同渠道跨商户重复流水号：正确业务隔离，保留原文与退款预占。
- 非流水号唯一键失败：不得一律伪装成渠道证据冲突。
- 大重复组 / 单条 / 多组及跨 operation：差错准确，读取不按重复组长度逐行累加。
- 详情同时间戳、多页及跨支付 / 商户游标：无重复或遗漏，每个返回凭证借贷完整。
- 非法 / 截断 UTF-8 与真正文件 I/O 故障：分别隔离 / 重试。

## Task 1: 编码错误隔离

**Files:** `ReconciliationService.java`、`ReconciliationTest.java`（由 MySqlReconciliationTest 继承）。

- [x] 增加非法字节、截断多字节、正常中文及原文件读取失败用例；损坏文件应 QUARANTINED、无差错发布、原件可取。
- [x] 运行 ReconciliationTest，记录损坏编码用例 RED。
- [x] 在解析边界把 CharacterCodingException 转成 InvalidBill；保留其他 IOException 的 FAILED / 重试路径。
- [x] 运行 H2 与 MySQL 对账回归，记录 GREEN。

## Task 2: 流水号冲突归一化与任务隔离

**Files:** `PaymentService.java`、`RefundService.java`、`TaskQueue.java`、`TaskWorker.java`、`ChannelEvidenceTest.java`、`DurableTaskTest.java`。

- [x] 覆盖支付 / 退款 × 同步 / Inbox × 同 / 不同商户重复流水号，检查无新增凭证、预占不变、原文与 CONFLICT 可见；记录 RED。
- [x] 仅包围更新流水号的 UPDATE 归一化唯一键错误，沿用 ApiException 的回滚与留证。
- [x] 证据冲突直接将当前任务置 DEAD，保留租约 fencing 和授权重放；其他异常继续正常重试。补实测此分支，先 RED 后 GREEN。
- [x] 执行 H2 与 MySQL 资金 / 任务 / 渠道回归。

## Task 3: 对账重复检测成本

**Files:** `ReconciliationService.java`、`ReconciliationTest.java`、MySQL 专项探针。

- [x] 使用第二轮已经 RED 的 MySQL 实际执行计划作为成本基线；增加单条、多个重复组、同编号不同 operation / 批次的真实匹配断言。
- [x] 将逐行 COUNT 改为寻找另一行的 EXISTS，仅返回重复标记。
- [x] 用当前 SQL 复测 2 万 / 4 万重复组；核对执行计划有界读取，保留前后日志；运行对账套件。

## Task 4: 完整凭证及关联记录分页

**Files:** `Queries.java`、`ConsoleController.java`、新 V11 索引迁移、`ConsoleDetailTest.java` / MySQL 变体、`console/src/lib/api.ts`、`console/src/pages/Payments.tsx` 及组件测试。

**Interfaces:** 原详情数组字段保留，新增 `nextCursors`；GET 同一路径增加 refundsCursor / journalsCursor / attemptsCursor / tasksCursor / conflictsCursor。各页按 created_at DESC,id DESC，游标指向当前支付范围内的一条记录，每类 50 条。entries 包含本页 journals 的全部分录。

- [x] 以真实控制器 / MockMvc 验证 300 次退款、完整凭证、多页无重漏、同时间戳及越权游标，先观察旧接口失败。
- [x] 实现范围校验、独立 seek 分页，保留每次详情读取的一致性快照；凭证查询增加 payment_id / created_at / id 索引。
- [x] 前端每类记录有上一页 / 下一页；凭证整页展示，不再按分录行切页；条件切换重置分页，时间线说明当前页范围。先写用户交互失败测试。
- [x] 执行 H2 / MySQL 详情回归、前端测试与生产构建，部署后执行浏览器流程及分页交互。

## Final verification

- [x] `scripts/mvn.ps1 test fmt:check -B -ntp` 全部后端检查。
- [x] `scripts/pnpm.ps1 test` 与 `scripts/pnpm.ps1 build`。
- [x] 一次独立审查；必要问题用 RED→GREEN 修复，不凑额外优化。
- [x] 同步审查修复验收、API / 运行约定与变更证据；记录真实结果，不认证未执行的 R1 / D1 / 四小时长稳。

## Execution ledger

- Pre-flight: Task 1 / 3 共用 ReconciliationService，按先后执行；Task 2 的领域异常必须与现有 Gateway / Inbox 的回滚留证兼容；Task 4 保留原字段及两参数控制器测试入口。
- Task 1: complete — 两个编码回归先 RED，随后 H2 / MySQL 对账 22/22 GREEN；缺失文件仍 FAILED，合法 UTF-8 中文按现有字段规则分类。
- Task 2: complete — 八条重复流水号场景、worker 冲突隔离先 RED；只在流水号 UPDATE 转换异常，其他凭证唯一键错误保持原类型。核心联合回归 95/95 GREEN；首次联合运行暴露 DurableTaskTest 原有微秒舍入边界，测试领取改用固定未来时钟。
- Task 3: complete — 实际 SQL 的 EXPLAIN ANALYZE 回归先显示 10,000,000 次索引行访问而 RED；EXISTS 后为约 500 次。同一回归扩展到 40,000 行及分散重复组后通过，没有新增聚合表。
- Task 4: implementation complete — 详情 / 前端分页均观察 RED；H2 / MySQL 详情与快照 5/5 GREEN，前端 18/18 GREEN。复用现有签名游标（绑定商户 / 支付 / 资源）替代初始设想的数据库游标定位，减少查询且范围可验证。
- Final: complete — 186/186 后端及格式检查、18/18 前端与构建、5 条浏览器流程通过（规模用例跳过 1 项）；独立审查的迁移冲突 RED→V11→GREEN；部署后健康 UP、借贷差额 0，详见第二轮审查修复验收与 round2-fixes-verification.json。
