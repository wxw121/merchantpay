# V1 代码审查记录（2026-10-18）

**后续状态：本文列出的 6 项 P2 已修复并验证。** 下文保留首次审查时的发现、位置及失败证据；最终实现与验证见文末“修复验收”。

范围：当前 `1.0.0-SNAPSHOT` 的支付、退款、账务、渠道交互、Inbox / Outbox、任务、权限、查询、对账、导出及 React 工作台。目录没有 Git 历史，因此审查对象为当前源码，不存在可提供的提交差异。金融与异步链路另经过一次独立上下文审查，主审已核对发现并运行隔离复现。

结论：确认 **6 项 P2（应修复）**；本次未确认 P0 / P1。建议先处理回执校验、关闭幂等、冲突证据和测试稳定性，再继续功能验收。本轮未修改业务实现或正式测试文件，仅新增本报告与 `.work` 中的隔离复现工具。未向正在运行的演示库写入测试业务。

原支付根行锁、退款预占与结转、事件唯一凭证、本地事务与 Outbox 的组合有明确实现；批任务的租约及执行 token 也有针对性测试。以下问题不会因这些机制存在而自动消失。

## 1. [P2] 错配的失败回执可被当作明确失败，并释放退款预占

- 位置：[ChannelGateway.java:205](../../platform/src/main/java/dev/merchantpay/platform/channel/ChannelGateway.java#L205)，关联 [RefundService.java:194](../../platform/src/main/java/dev/merchantpay/platform/refund/RefundService.java#L194)。
- 触发：原退款为 `8000 CNY`，收到渠道、退款号匹配，但金额为 `1`、币种为 `USD` 的 `FAILED` 回执。
- 问题：归一化只有 `SUCCEEDED` 保留金额和币种；`FAILED` 转为只有渠道和错误码的 `ChannelResult.Failure`。后续领域逻辑无法再验证原字段，会将退款置为 `FAILED` 并释放 8000 分预占；支付的失败分支也存在相同字段校验缺口。
- 影响：把尚未正确匹配的证据当成确定失败，允许再次使用原预占额度。不能据此断言已经发生实际超退，但该转换不满足需求 §5.2 的证据匹配约束。
- 复现：调用真实 `ChannelGateway.apply` 与真实领域服务，实际结果为 `FAILED`、预占 `0`、冲突记录 `0`；数据库为隔离 H2。
- 建议：在归一化丢弃字段前，完成对应终态所需的金额、币种和业务关联校验。错配失败进入冲突、保留预占；尚未发送支付即关闭的零金额 tombstone 应保留明确的独立规则。
- 补测：支付 / 退款的错金额、错币种 `FAILED`，覆盖同步与 Inbox 路径；现有成功证据测试不能替代失败证据测试。

## 2. [P2] 关闭超时后再次关闭会撞 Outbox 唯一键

- 位置：[PaymentService.java:276](../../platform/src/main/java/dev/merchantpay/platform/payment/PaymentService.java#L276)、[PaymentService.java:296](../../platform/src/main/java/dev/merchantpay/platform/payment/PaymentService.java#L296)。
- 触发：第一次 `requestClose` 写入固定事件 `channel-close:<id>`；关闭或并发查单遇到网络异常，Gateway 应用 `Unknown`；客户端再次调用关闭。
- 问题：`Unknown` 把 `CLOSE_PENDING` 改为 `UNKNOWN`，而关闭的幂等判断只识别 `CLOSED` / `CLOSE_PENDING`。重试重新插入同一事件，违反 `uq_outbox_event`。
- 影响：重复关闭抛 `DuplicateKeyException`，接口返回服务器错误，当前状态因事务回滚仍为 `UNKNOWN`。原关闭任务没有丢失，但接口失去幂等性。
- 复现：`createPayment → requestClose → applyResult(Unknown) → requestClose`，稳定得到上述异常。
- 建议：未知观察保留已经发出的关闭意图，并结合已存在关闭请求保证幂等；不要单纯删除唯一约束或重新生成事件号。
- 补测：加入关闭超时、并发查单与客户端重复关闭的服务及 HTTP 回归。

## 3. [P2] 同步回执身份错配时丢失原始冲突证据

- 位置：[ChannelGateway.java:97](../../platform/src/main/java/dev/merchantpay/platform/channel/ChannelGateway.java#L97)。
- 触发：渠道同步回执中的 `aggregateId`、`operation` 或 `channel` 与当前请求不符。
- 问题：`verifyIdentity` 先于 `response_body` 写入执行；其异常直接离开 `execute`，没有经过 `apply` 中的独立冲突事务。
- 影响：资金状态不会因该回执错误转换，但渠道尝试仍保留 `PREPARED` 或旧响应，`evidence_conflicts` 也没有原文。后续正常重试可能完成任务，异常回执随后无法追溯。
- 复现：用 ChannelApi 测试替身返回错误聚合 ID，调用真实 `execute`，得到 `CHANNEL_EVIDENCE_CONFLICT`，同时冲突记录为 `0`、`response_body=NULL`。
- 建议：保存原始响应并按当前请求的预期聚合记录错配，统一身份校验与领域校验的证据留存入口。
- 补测：通过 `execute` 分别注入三种身份错配。现有直接调用 `apply` 的测试没有覆盖这一分支。

## 4. [P2] 超过 100 家商户后，平台操作表单无法选择其余商户

- 位置：[App.tsx:78](../../console/src/App.tsx#L78)，关联 [ConsoleController.java:184](../../platform/src/main/java/dev/merchantpay/platform/web/ConsoleController.java#L184)。
- 触发：商户总数超过 100，目标商户位于按 ID 排序的前 100 家之外。
- 问题：全局商户查询只请求无搜索参数的 `/merchants`，服务端固定 `LIMIT 100`；上下文和全局选择器只保存这次结果。支付、对账、导出表单复用同一组选项，没有远程搜索或分页。
- 影响：管理员能在商户目录按名称搜索到并配置目标商户，却无法在这些业务表单中选中它。扩大数据量后，已有功能不能覆盖全部商户。
- 验证：静态追踪服务端查询、App 上下文及三个表单的 options；本轮没有为验证而向演示库插入 101 家商户。
- 建议：提供带搜索与分页的商户选择组件，并支持按 ID 获取 / 回显当前选项，避免简单无限调大 LIMIT。
- 补测：101 家以上的选项数据，验证管理员为首屏外商户创建支付、对账和导出。

## 5. [P2] 列表没有日期筛选入口，七天以前的记录无法在工作台查找

- 位置：[components.tsx:86](../../console/src/components.tsx#L86)、[components.tsx:105](../../console/src/components.tsx#L105)。
- 问题：公共 `PagedTable` 只接收商户、状态、游标等参数，没有开始 / 截止时间字段或控件，也不发送 `from` / `until`。所有页面因此固定使用服务端默认最近七天。
- 影响：不满足需求 §4.7 的在线日期筛选要求；七天以前的任务重放、对账批次及审计查询没有 UI 入口。支付 / 退款导出日期表单不能代替这些在线列表。
- 验证：静态追踪所有 PagedTable 调用与后端 `Queries.range`；未宣称做过历史数据的浏览器验收。
- 建议：增加日期范围，明确北京时间与 UTC 转换；变更条件时重置游标，并将时间范围纳入查询键与请求参数。
- 补测：七天以前、31 天边界、时间条件变化后游标重置及翻页保留范围。

## 6. [P2] 导出测试依赖立即领取，时间精度舍入导致不稳定失败

- 位置：[ExportTest.java:53](../../platform/src/test/java/dev/merchantpay/platform/ExportTest.java#L53)、[ExportTest.java:73](../../platform/src/test/java/dev/merchantpay/platform/ExportTest.java#L73)。
- 现象：全套平台测试在第 73 行 `getFirst()` 抛 `NoSuchElementException`；单独复跑 ExportTest 时，第 53 行出现同类错误。
- 根因：Outbox 使用系统时钟写入 `TIMESTAMP(6)`，测试随即使用另一次系统时钟读数查询 `due_at <= now`，假定一定能领到任务。隔离诊断实测存储值为 `2026-10-18T07:32:25.139648Z`，领取查询时间为 `2026-10-18T07:32:25.139647800Z`，相差 **200 ns**。舍入后的任务尚未到期，空结果是合法的。
- 影响：导出权限与租户隔离测试可能在真正执行断言前失败，阻断后端验证。不能由此推断生产任务永久丢失，生产轮询会再次领取。
- 建议：使用可控时钟、明确设置任务到期，或等待“任务可领取”的条件；不要用固定 sleep 或盲目重跑掩盖问题。
- 补测：将时钟和数据库精度边界纳入领取测试，统一其他同类夹具。

## 本轮验证与证据

| 检查 | 本轮结果 | 证据 |
| --- | --- | --- |
| 平台完整测试 | 97 项，96 通过、1 异常；构建失败，模拟器被该次 reactor 跳过 | [首次日志](../../.work/code-review-backend.log) |
| ExportTest 单独复跑 | 5 项，4 通过、1 异常；失败位置变化，仍是立即领取为空 | [复跑日志](../../.work/code-review-export-rerun.log) |
| 模拟器测试及格式检查 | 4 项通过，格式检查通过 | [模拟器日志](../../.work/code-review-simulator.log) |
| 前端 Vitest | 2 个测试文件、12 项通过 | 本轮工具输出 |
| 业务缺陷及测试时序隔离复现 | 三条业务路径和 200 ns 到期边界均已复现 | [复现源码](../../.work/dev/merchantpay/platform/ReviewProof.java)、[结果](../../.work/code-review-reproductions.log) |

本轮未重新执行浏览器 E2E、生产构建、OSV 审计或性能负载，不把历史通过记录冒充本次通过。平台的 fmt 阶段因测试失败未执行完成，也不宣称全部格式检查通过。

## 考虑后未另列为新缺陷的事项

- 已授权的本地 `admin/admin` 为用户明确要求，本轮不作为弱口令问题。
- 重复通知属于至少一次语义，接收方需按 eventId 去重。
- 查单重试上限及 DEAD 后人工查证 / 重放已有明确运行约定，未当成自动释放未知资金的缺陷。
- 失租后的金融重复执行有根支付锁和唯一事件保护，未证明重复记账；批任务另有 fencing。
- 分笔手续费因确认顺序产生的差异已声明由对账呈现，累计规则另有测试。
- 签名关闭 API 缺独立操作审计为旧审查已登记小项，不算本轮新发现。
- MySQL 对账快照使用批量 INSERT SELECT 的锁影响，以及重复记录密集账单的匹配成本，本轮未做专项混合负载验证；不把推测写成已证明的快照撕裂或吞吐结果。
- 非法时间参数的 HTTP 错误映射没有完成动态验证，本轮未列为确定发现。
- S3、正式支付沙箱、审核结算及细分角色遵循已声明版本范围；R1 / D1 / 四小时长稳依旧未验收，本次功能审查不能豁免这些发布门槛。

## 修复验收（2026-10-18）

用户明确授权修复以上六项，仍保持 `1.0.0-SNAPSHOT`，没有扩大到 V2 或调整既定性能门槛。

| 问题 | 实际修复 | 回归覆盖 |
| --- | --- | --- |
| 错配失败回执释放预占 | 成功、失败、关闭终态在归一化前匹配渠道、金额、币种；模拟渠道零金额关闭 tombstone 需已有关闭意图 | 支付 / 退款 × 错金额 / 错币种 × 同步 / Inbox；正确失败幂等；零金额关闭边界；H2 与 MySQL |
| 关闭重试撞唯一键 | Unknown 保留 CLOSE_PENDING；旧 UNKNOWN 数据在已有关闭事件时复用原请求 | 两种状态下重复关闭，检查唯一关闭事件；H2 与 MySQL |
| 身份冲突原文丢失 | 回执先保存再验证，冲突绑定预期聚合，尝试标 CONFLICT；前置查询也必须通过验证才能提交下一次调用 | 三种身份错配；支付 / 退款 NOT_FOUND 及关闭前 PENDING 错配均禁止后续调用；核对单次冲突原文；H2 与 MySQL |
| 商户选项局限 100 家 | 共享远程搜索组件、精确 ID 回显接口、完整编号优先匹配；沿用服务端租户限制 | 102 家商户及名称重叠搜索；跨商户 404；React 真组件搜索 / 回显；管理员三个业务表单的浏览器交互 |
| 缺日期筛选 | 北京时间日期范围、最多 31 天、截止日不含；查询键与游标范围一致，变更回到第一页；非法时间返回 400 | 旧日期查询、UTC 转换、旧游标清除、超范围拒绝；浏览器实际请求与 375px 布局 |
| 导出测试不稳定 | 仅任务写入 / 领取使用同一微秒精度固定时钟，保留真实数据查询时钟，显式设置租约 / 到期边界 | 原导出权限、快照与 fencing 用例在 H2 / MySQL 全部通过，无 sleep 或自动重跑掩盖失败 |

独立复核最初指出中间查单回执仍可能被覆盖，已新增三条回归先观察 RED，再补齐逐次保存和校验；同一复核席位复查后未发现新的 P1 / P2。校验与领域冲突统一留存，校验位于领域异常捕获之外，不重复插入冲突。原 MySQL 外层连接解析商户、独立事务 VALUES 插入的规则保持不变。

最终源码验证：

- 后端全量 **143 项通过**（平台 139 + 模拟器 4），格式检查通过：[最终日志](../../.work/review-fixes-verified-backend.log)。
- 前端 **16 项通过**：[最终日志](../../.work/review-fixes-final-ui.log)；TypeScript 与生产构建通过：[构建日志](../../.work/review-fixes-ui-build.log)。原有大包体积提示仍存在。
- 最终部署后的浏览器 **4 条流程通过**，覆盖响应丢失 / 关闭、支付退款对账导出、跨商户隔离、管理员搜索与历史日期查询：[完整日志](../../.work/review-fixes-verified-e2e.log)。十万行规模用例本轮未启用，明确跳过 1 项。
- 表格加载过渡结束后重新截图，专项 **1 条通过**：[视觉日志](../../.work/review-fixes-final-visual.log)、[桌面截图](../releases/evidence/review-date-filter-desktop.png)、[375px 截图](../releases/evidence/review-date-filter-mobile.png)。
- 本地最终版本已启动：[部署日志](../../.work/review-fixes-final-deploy.log)；健康 UP，全局借贷差额 0：[只读核对](../../.work/review-fixes-health.log)。

初次测试失败、夹具时钟调整过程中失败及浏览器定位失败均保留在 `.work/review-fixes-*.log`，不覆盖为“始终通过”。本轮没有重新认证性能、依赖漏洞、D1 或四小时长稳。
