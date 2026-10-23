# 第二轮代码审查（2026-10-18）

**当前状态：以下四项已修复并完成本地验收，详见文末“修复验收”。原审查发现与失败证据保留。**

本轮确认 **4 项新的 P2**，未确认新的 P0 / P1。审查对象是上一轮六项修复后的当前源码，覆盖支付、退款、渠道查询 / Inbox、任务重试、对账、导出、查询与管理端详情。另由独立审查席位复核资金核心及模拟渠道，并对候选问题交叉质疑、去重。

本轮只增加审查报告和 `.work` 中的隔离复现程序、日志；未修改业务源码或正式测试，未写入演示数据库，也未重启运行中的应用。以下结论区分账务数据、证据展示和任务处理，未把展示不完整或冲突分类错误解释为已发生资金损失。

## 1. [P2] 跨业务重复渠道流水号没有进入证据冲突处理

- 位置：[ChannelGateway.java:177](../../platform/src/main/java/dev/merchantpay/platform/channel/ChannelGateway.java#L177)，同步异常处理位于 [112 行](../../platform/src/main/java/dev/merchantpay/platform/channel/ChannelGateway.java#L112)；关联写入为 [PaymentService.java:251](../../platform/src/main/java/dev/merchantpay/platform/payment/PaymentService.java#L251) 和 [RefundService.java:185](../../platform/src/main/java/dev/merchantpay/platform/refund/RefundService.java#L185)。
- 触发：同渠道的不同支付，或不同退款，收到身份、金额和币种都匹配，但 `reference` 被另一笔业务使用的成功回执。
- 根因：`uq_payment_receipt` / `uq_refund_receipt` 正确拒绝重复流水号，但抛出的 `DuplicateKeyException` 没有转换成 `CHANNEL_EVIDENCE_CONFLICT`。网关仅捕获 `ApiException` 留存领域冲突；Inbox 也只对该领域错误标记 `CONFLICT`。
- 实际结果：同步路径将第二笔业务转为 `UNKNOWN`，渠道尝试停在 `PREPARED`，冲突记录为 0；Inbox 路径回滚业务事务，原业务仍为 `PROCESSING`，通知仍为 `RECEIVED`，冲突记录同样为 0。后台继续重试一个确定性的关联冲突，最终可进入 DEAD，操作人员只能从数据库错误和原文查证，无法从冲突视图定位。
- 隔离验证：支付 / 退款 × 同步 / Inbox 四条路径均已复现；新增凭证均为 0，退款预占保持。同步 `response_body` 和 Inbox 原文仍保留，不能称为证据全部丢失，也未证明重复入账或超退。
- 建议：保留唯一约束，准确识别这两个约束对应的跨业务流水关联冲突，在业务事务回滚后统一留证并标记冲突。不要把所有 `DuplicateKeyException` 都当作渠道冲突；仅做预查询也无法替代并发唯一约束。
- 回归要求：覆盖同 / 不同商户的跨支付、跨退款重复流水号，包含同步和 Inbox；断言资金与预占不变、原文可查、关联到正确聚合、冲突状态可见。

## 2. [P2] 对账逐行重复计数使密集重复账单产生平方级读取成本

- 位置：[ReconciliationService.java:445](../../platform/src/main/java/dev/merchantpay/platform/reconciliation/ReconciliationService.java#L445)。
- 触发：账单在同一批次中包含大量相同 `(operation, aggregate_id)` 的记录。这是系统明确支持识别的 `DUPLICATE_CHANNEL_RECORD` 差错；当前解析允许这类记录进入匹配。
- 根因：每返回一行，相关子查询都对该业务号的全部账单行执行 `COUNT(*)`。`LIMIT 500` 仅限制外层返回行，已有匹配索引没有消除重复统计同一组的成本。
- 实测：一次性 **MySQL 8.4.11** 容器中，`subquery_to_derived` 关闭；使用项目 V4 表结构、索引与第 445 行的原始 SQL，执行 `EXPLAIN ANALYZE`：

| 批次构造 | 外层本次返回 | 计数索引读取的实际行数 / 次 | 计数执行次数 | 整条 EXPLAIN ANALYZE 耗时 |
| --- | ---: | ---: | ---: | ---: |
| 20,000 个不同业务号 | 500 | 1 | 500 | 8 ms |
| 同业务号重复 20,000 行 | 500 | 20,000 | 500 | 2,747 ms |
| 同业务号重复 40,000 行 | 500 | 40,000 | 500 | 5,175 ms |

- 影响：最后一项仅取首 500 行，就发生约 **2,000 万次匹配索引行访问**。按本次实际执行方式，单组 k 条重复记录全部处理时，计数读取成本为 k²；这一复杂度结论来自计划中的实际行数与循环次数。重复记录多时会长时间占用数据库及对账工作槽，500 行分批无法控制每批数据库工作量。
- 范围限制：上述耗时包含执行计划诊断开销，只测读取 SQL，不含账单解析、差错写入或整个批次执行；没有外推整批耗时、R1 / D1 是否达标或支付 TPS，也没有宣称所有优化器设置都会选择相同计划。
- 建议：按批次和业务键一次预聚合重复计数，或使用经真实执行计划验证、不会逐行重扫重复组的匹配方案。聚合应可供后续分页复用，不能简单地在每一页重做整个批次的聚合。
- 回归要求：在 MySQL 上加入大量不同业务号、单个大重复组及多个倾斜重复组，核对差错准确性、续跑幂等及随规模增长的实际读取成本。

## 3. [P2] 支付详情按行截断分录，会返回不完整的记账凭证

- 位置：[ConsoleController.java:118](../../platform/src/main/java/dev/merchantpay/platform/web/ConsoleController.java#L118)，相关退款与凭证上限位于 [110 行](../../platform/src/main/java/dev/merchantpay/platform/web/ConsoleController.java#L110) 和 [114 行](../../platform/src/main/java/dev/merchantpay/platform/web/ConsoleController.java#L114)。前端详情在 [Payments.tsx:529](../../console/src/pages/Payments.tsx#L529) 展示分录。
- 触发：一笔支付产生较多部分退款。接口分别截取 200 条退款、200 个凭证、600 条分录，分页范围互不关联，也没有继续读取的游标或 `hasMore`。
- 隔离复现：创建 10,000 分支付，并通过实际领域服务完成 300 笔各 1 分的退款。数据库有 300 条退款和 603 条分录，全量借贷差额为 **0**；真实控制器只返回 200 条退款和 600 条分录。最后一个已展示凭证实际有 2 条分录，接口只给出 1 条，返回分录借贷差额为 **1 分**。
- 影响：管理端无法通过详情分页查看该凭证的另一条分录和遗漏的退款；凭证集合与分录集合也可能不对应，削弱交易查证完整性。这是查询 / 展示缺陷，存储的账务依然平衡。
- 已考虑：前端确实写明“最多展示 600 条分录”，所以不把它描述成完全没有上限提示；问题是按分录行截断会拆开凭证，且没有获取完整结果的交互。Ant Design 的表内分页仅翻阅已收到的 600 条。
- 建议：按完整凭证游标分页，再取该页凭证的所有分录；为退款及其他时间线集合提供服务端分页和明确的剩余数据提示。保留响应大小限制，不改成一次返回全部数据。
- 回归要求：超过 200 笔退款、超过 600 条分录，覆盖含手续费的不同分录模板，验证每个展示凭证的分录完整、可继续翻页且租户隔离不变。

## 4. [P2] 非法 UTF-8 账单被误判为存储读取故障并反复重试

- 位置：[ReconciliationService.java:197](../../platform/src/main/java/dev/merchantpay/platform/reconciliation/ReconciliationService.java#L197)，严格解码器设置位于 [273 行](../../platform/src/main/java/dev/merchantpay/platform/reconciliation/ReconciliationService.java#L273)。
- 触发：文件摘要匹配，账单元数据和列头正确，但内容含非法 UTF-8 字节。
- 根因：解码器使用 `CodingErrorAction.REPORT`，抛出的 `MalformedInputException` 属于 `IOException`。它绕过 `InvalidBill` 的隔离分支，被统一归为“读取原始账单失败”，批次置 `FAILED` 并抛异常；任务执行器随后重试相同文件。
- 隔离验证：构造正确 SHA-256、有效元数据与列头，追加 `0xff`。实际处理结果为 `FAILED`，原因归为读取失败，底层异常为 `MalformedInputException`，原文件仍存在。
- 影响：确定性损坏输入没有进入 FR-20 约定的 `QUARANTINED` 流程，错误原因误导排障，并消耗自动重试；没有证据表明它会发布半份有效对账结果。
- 建议：将字符编码错误识别为永久账单格式错误，保留原文件与明确编码错误原因并隔离；真正的文件 / 存储 I/O 故障仍保留重试能力。
- 回归要求：非法字节、截断的多字节字符、正确 UTF-8 中文内容及真实读取故障，分别验证隔离、原文留存、错误分类与重试行为。

## 本轮证据与验证边界

| 检查 | 结果 | 证据 |
| --- | --- | --- |
| H2 隔离复现 | 六个业务场景的预期缺陷断言全部成立，程序退出 0 | [源码](../../.work/dev/merchantpay/platform/SecondReviewProof.java)、[日志](../../.work/second-review-reproductions.log) |
| MySQL 匹配查询专项 | 独立容器内三种数据分布，取得实际执行计划，程序退出 0 | [源码](../../.work/dev/merchantpay/platform/SecondReviewMysqlCostProof.java)、[日志](../../.work/second-review-mysql-cost.log) |
| 独立审查席位 | 确认流水号冲突候选，排除公开 URL 校验误报；由主审补齐动态复现 | 本次审查记录 |
| 完整后端 / 前端 / 浏览器回归 | 本轮没有重新执行 | 上一轮 143 项后端、16 项前端及浏览器通过记录仅为历史基线 |
| R1 / D1 / 四小时长稳 / 依赖审计 | 本轮没有重新认证 | 原发布门槛保持 |

复现使用已有编译产物与对应源码，创建独立 H2 数据库或一次性 MySQL 容器；不是对运行中的演示系统做破坏性验证。MySQL 容器在程序结束时关闭。初版复现遇到 Mockito 动态 attach 受限，后改用 JDK 接口代理；MySQL 探针初版 JDBC 参数分隔符错误也已修正。失败诊断保留在 `.work/second-review-mockito-attach-failed.log` 与 `.work/second-review-mysql-probe-url-failed.log`，未将其列为产品缺陷。

## 已考虑但未另列为新缺陷

- 上一轮的终态金额 / 币种校验、关闭重试、中间回执留存、商户搜索、日期筛选和测试时钟问题已有修复，本轮不重复计数。
- 两个公开创建入口均验证通知 URL；只看到内部服务缺少同一校验不足以证明可绕过 SSRF 策略。
- 根支付锁、退款额度预占与事务回滚未发现新的超退路径；本轮唯一键冲突也没有绕过这些保护。
- 失租后重复通知属于已声明的至少一次语义；自动任务 12 次后 DEAD 及授权重放本身不作为新问题。
- MySQL 快照 `INSERT SELECT` 的并发锁影响未完成混合负载专项，不能由本次匹配查询计划推导出快照撕裂或支付吞吐结论。
- 通知重试期间重新读取业务快照未被证明违反当前事件契约；未将潜在契约改进直接认定为缺陷。
- 本地 `admin/admin`、模拟渠道、本地文件存储、细分角色、正式沙箱和 V2 审核结算遵循用户授权或既定版本范围。

## 修复验收（2026-10-18）

用户已授权修复以上四项。实现保留现有版本和资金约束，新增索引迁移为 **V11**，原 V10 导出执行迁移不变。

| 原问题 | 修复结果 | 回归覆盖 |
| --- | --- | --- |
| 重复流水号未隔离 | 仅在更新支付 / 退款流水号的 UPDATE 处转换唯一键冲突，业务事务回滚后沿用独立事务留证；同步尝试 CONFLICT，当前任务直接 DEAD；Inbox 记录 CONFLICT 后停止处理 | 支付 / 退款 × 同步 / Inbox × 同 / 不同商户八条路径；原文、归属、凭证不增加、预占保留；非流水唯一键错误保持原类型；worker 网络故障仍退避；旧租约不能隔离新任务 |
| 重复检测反复计数 | 改为 EXISTS 查找另一条同批次 / 同 operation / 同业务号记录，不增加聚合表、缓存或中间状态 | 逐条差错结果及重跑幂等；真实 MySQL 对生产 SQL 的执行计划检查：2 万、4 万及分散重复组；原 2 万组读取 500 行时约 1,000 万次索引行访问，修复后约 500 次 |
| 详情截断凭证 | 各类记录分别按时间和 ID 的签名游标分页；entries 包含本页 50 个完整凭证的所有分录，UI 提供前后翻页并说明当前页范围 | 300 笔退款、301 个凭证、603 条分录以及超过一页的尝试 / 任务 / 冲突全部可达；同时间戳不重漏；所有展示凭证借贷完整；跨支付、跨集合及篡改游标拒绝，跨商户 404；MySQL 并发快照仍成立 |
| 损坏编码被重试 | 在解析边界将 CharacterCodingException 归为 InvalidBill，进入 QUARANTINED，保留原件 | 非法字节、截断多字节编码；合法 UTF-8 中文按原字段规则分类；原件缺失仍是 FAILED / 可重试 I/O 故障 |

新增详情游标签名绑定商户、支付与集合，一小时有效；每次响应仍是 REPEATABLE_READ 一致性快照，跨页提供实时查证视图。前端切换支付 / 商户重建详情状态，刷新回到首页；模拟收银台继续只读取原 `payment` 字段。

独立审查发现初版索引迁移错误复用了 V10，真实 Flyway 新库迁移先复现 RED，改 V11 后成功执行全部 11 个迁移。仅清理改名前残留的单个 target 生成资源，未删除旧业务迁移或数据。首次完整回归因此出现的安全上下文失败保存在 [失败日志](../../.work/round2-fixes-full-migration-failed.log)，不作为成功结果覆盖。首次渠道 / 任务联合回归还暴露已有测试的微秒时钟舍入问题，DurableTaskTest 领取使用固定未来时钟后通过，无生产轮询改动或固定 sleep。

复核结论：迁移号问题修复后，没有确认其余待修复 P1 / P2。Inbox 冲突处理任务 DONE 与通知本身 CONFLICT 并不矛盾；PROCESS_INBOX 未进入原详情任务关联集合是既有范围，本次没有扩大；没有外推所有数据分布下的成本、超一小时游标或未执行的前端延迟场景。

最终验证：

- 后端 **186 项通过**（平台 182 + 模拟器 4），格式检查通过：[完整日志](../../.work/round2-fixes-final-backend.log)。
- 前端 **18 项通过**：[测试日志](../../.work/round2-fixes-final-ui.log)；TypeScript 与生产构建通过：[构建日志](../../.work/round2-fixes-final-ui-build.log)。jsdom 伪元素提示及已有大包体积提示仍在，未将其隐藏。
- 浏览器 **5 条流程通过，1 条规模用例明确跳过**：[日志](../../.work/round2-fixes-final-e2e.log)。覆盖 51 笔真实模拟退款的前后翻页 / 整凭证、响应丢失与关闭、支付退款对账导出、跨商户隔离、管理员搜索和历史日期。
- 桌面和 375px 布局截图已人工检查：[桌面](../releases/evidence/round2-evidence-desktop.png)、[移动端](../releases/evidence/round2-evidence-mobile.png)。浏览器同时断言无页面异常和页面水平溢出，不能仅由截图推断功能通过。
- 已部署修复版：[部署日志](../../.work/round2-fixes-final-deploy.log)；现有 MySQL 从 V10 升级到 V11，服务健康 UP、全局借贷差额 0、不平衡凭证 0、异常退款额度 0：[只读核对](../../.work/round2-fixes-runtime.log)。
- 迁移 [RED](../../.work/round2-fixes-migration-red.log) / [GREEN](../../.work/round2-fixes-migration-green.log)、[结构化验收](../releases/evidence/round2-fixes-verification.json)及[证据摘要](../releases/evidence/round2-SHA256SUMS)已保存。

本轮没有重新认证 R1 / D1、十万行整批规模、四小时长稳或依赖漏洞。专项执行计划改善不等于这些发布门槛已达成。初次沙箱文件移动失败、计划解析器大小写修正、前端测试定位 / 类型错误和迁移失败日志均留存 `.work/round2-fixes-*`，最终通过记录只对应上述最终命令与部署。
