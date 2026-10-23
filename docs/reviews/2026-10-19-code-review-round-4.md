# 第四轮代码审查（2026-10-18 至 19 日）

**当前状态：本轮 P3 已修复并部署，原审查证据保留；修复验收见文末。**

本轮确认 **1 项新的 P3**，未确认新的 P0 / P1 / P2。审查对象是第三轮四项修复并部署后的当前源码，与前三轮共十四项已修复问题去重。没有为凑数量将未验证的容量风险、风格建议或已接受的版本取舍列为缺陷。

审查阶段只增加本报告和 `.work` 中的隔离验证产物，未修改业务源码或正式测试，未重启服务，未写入运行中的演示数据库。资金、渠道证据、任务与对账由主审检查；独立审查席位复核签名、权限、商户、通知、查询、导出及管理端源码，并完成下面的隔离复现。没有 Git 提交范围，审查以实际文件和上一轮源文件摘要为准；后续授权修复见文末。

## 1. [P3] 平台用户下载导出时，审计记录没有关联实际商户

- 位置：[ReconciliationController.java:220–221](../../platform/src/main/java/dev/merchantpay/platform/web/ReconciliationController.java#L220)。权限范围在 [218 行](../../platform/src/main/java/dev/merchantpay/platform/web/ReconciliationController.java#L218) 取得，导出鉴权在 [219 行](../../platform/src/main/java/dev/merchantpay/platform/web/ReconciliationController.java#L219) 完成。
- 触发：没有固定 `merchantId` 的平台 ADMIN / FINANCE 用户，为商户 A 创建导出，再正常下载自己发起且仍有效的文件。即使当前工作台已选择 A，下载链接本身也不传商户参数。
- 根因：`access.merchant(null, false)` 对平台用户返回 `null`，表示允许在平台范围内查询。控制器将该授权查询范围同时传给 `exports.file` 和 `merchants.audit`，把“查询不限商户”误当成“被下载文件不属于任何商户”。导出记录实际上保存了非空 `merchant_id`。
- 实际结果：下载鉴权仍正常，返回 HTTP 200 和真实 ZIP；`EXPORT_DOWNLOAD` 审计的操作人、资源 ID 正确，但 `merchant_id=NULL`。由于 [Queries.java:179–181](../../platform/src/main/java/dev/merchantpay/platform/query/Queries.java#L179) 按 `merchant_id` 筛选，商户 A 的审计列表无法显示该次下载，全局审计仍可查到。
- 隔离复现：使用实际 `ExportService` 创建并生成属于 `merchant-a`、发起人为平台管理员的导出，通过实际控制器和 MockMvc 下载，再通过实际 `Queries.list("audit", ...)` 查询：

| 检查 | 结果 |
| --- | --- |
| 下载响应 | HTTP 200，492 字节 ZIP |
| 下载审计操作 / 操作人 / 资源 | EXPORT_DOWNLOAD，操作人与导出 ID 均正确 |
| 审计 merchant_id | NULL |
| merchant-a 的审计查询 | 0 条 |
| 全局审计查询 | 1 条 |

- 影响：管理员或财务人员按商户追查数据下载时会漏掉记录，商户视图的操作审计不完整。这是商户归属与查询可见性问题，没有证明越权下载、全局审计丢失或资金损失，因此定为 P3。
- 建议：继续使用当前查询范围实施权限校验；写审计时使用已授权导出记录的实际商户 ID。不要直接信任前端补传的商户 ID，也不要仅取消审计列表的租户过滤。
- 回归要求：平台用户下载商户 A 的自有导出后，A 的审计列表包含记录、B 不包含，全局包含；固定商户用户的下载审计维持原有归属；其他发起人、过期或跨商户请求仍被拒绝。

## 审查阶段验证与边界

| 检查 | 结果 | 证据 |
| --- | --- | --- |
| 针对性核心回归 | 93 项通过，失败 / 错误 / 跳过均为 0：AuditTransaction 9、ChannelEvidence 31、ConsoleDetail 2、DurableTask 5、Export 5、FinancialCore 15、Reconciliation 26 | [执行日志](../../.work/round4-core-regression.log) |
| Java 格式 | `fmt:check` 通过，60 个文件、0 个不合规 | 同上 |
| 导出审计隔离复现 | 私有 H2、真实服务 / 控制器 / 查询，缺陷断言全部成立，程序退出 0 | [源码](../../.work/round4-export-audit/Round4ExportAuditProof.java)、[日志](../../.work/round4-export-audit-proof.log) |
| 源码一致性 | 101 个源文件与第三轮修复后的摘要一致，变更 0 | [摘要验证](../../.work/round4-source-verification.json) |
| 完整 MySQL、前端及真实浏览器回归 | 本轮没有重跑；前端为源码审查，以上 93 项为 H2 回归 | 上一轮 232 项后端、25 项前端及 5 条真实浏览器流程仅为[历史基线](../releases/evidence/round3-fixes-verification.json) |
| 性能 / 规模 / 长稳 / 漏洞审计 | 本轮未重新认证 R1 / D1、十万行整批规模、四小时长稳或依赖漏洞状态 | 原发布门槛保持 |

核心回归命令：

```powershell
& scripts/mvn.ps1 -MavenArguments @('-pl', 'platform', '-Dtest=FinancialCoreTest,ChannelEvidenceTest,DurableTaskTest,ReconciliationTest,AuditTransactionTest,ConsoleDetailTest,ExportTest', 'test', 'fmt:check', '-B', '-ntp')
```

复现程序使用对应编译产物，在独立 H2 数据库和 `.work` 临时目录中生成真实导出；通过 standalone MockMvc 调用控制器，不发出真实后端请求。主审核对了探针源码、断言、日志及源文件位置，没有仅依赖独立审查席位的口头结论。

## 已考虑但未另列的新缺陷

- 前三轮十四项修复、签名关闭审计补齐均未重复计数。本轮下载商户归属问题与上一轮业务 / 审计分开提交的根因不同：下载审计已经写入，缺的是商户关联。
- 支付终态、退款根支付锁与预占、唯一记账、关闭稳定请求键、渠道证据冲突及批任务租约路径，未确认新的重复扣款、超退或失租后发布结果问题。
- 对账处理确实先复验存储文件 SHA-256，再解析；没有将“未复验摘要”作为发现。LF / CRLF、字段内部 CR、行长和检查点回归仍通过。
- 并发渠道观察可能使尝试记录保存较晚返回的 PENDING，业务终态仍保持；没有证明当前“最近一次观察”的展示契约要求该辅助状态单调，也没有证明资金回退，未将其提升为确定缺陷。
- 通知至少一次、12 次后 DEAD 与人工重放、当前快照通知、按 key 限流、每次响应一致但跨页实时的游标，都沿用现有契约；未将其改造建议当成新缺陷。
- 导出发起人、商户范围、有效期及签名游标的约束仍存在；没有确认新的跨商户或跨用户下载路径。
- 需要真实混合负载验证的 MySQL 锁成本、模拟渠道大账单内存成本及 CSS / 首包性能，不由本次静态阅读推断达标或故障。
- 商户目录可通过搜索恢复的前 100 条过滤、没有表格执行证据的 Unicode CSV 公式变体，以及本地身份 / HTTP、细分角色、会话撤销、正式沙箱和 V2 审批结算，沿用此前说明，不另列为本轮发现。

审查阶段未实施上述 P3 的修复。随后用户授权修复，处理如下。

## 修复验收（2026-10-19）

下载控制器仍先用当前身份范围、发起人、有效期和完成状态检查导出。检查通过后，按已授权的导出 ID 查询实际 `merchant_id` 写入审计，不再使用平台用户可能为空的查询范围作为资源归属。导出归属在应用中不可修改，因此不需要新增服务接口、表结构或迁移；版本仍为 `1.0.0-SNAPSHOT`。

新增后端用例覆盖 ADMIN / FINANCE / MERCHANT 下载成功后的实际商户、操作人、资源、ZIP 响应及商户 / 全局审计可见性；另验证其他商户、其他发起人、已过期导出仍被拒绝且不产生下载审计。这六个场景随现有 MySQL 继承套件运行。新增真实浏览器用例以管理员登录，为商户 B 创建导出并下载，确认 B 和全局审计包含记录、A 不包含。

| 验证 | 结果 | 证据 |
| --- | --- | --- |
| 修复前 H2 回归 | 11 项中 2 项失败、0 错误，ADMIN / FINANCE 审计商户为空 | [红灯日志](../../.work/round4-fix-red.log) |
| 修复前真实浏览器 | 商户审计列表没有下载记录，新增用例失败 | [浏览器红灯](../../.work/round4-fix-browser-red.log) |
| 修复后导出专项 | H2 11 项 + MySQL 11 项，共 22 项通过，格式通过 | [日志](../../.work/round4-fix-export-green.log)、[后端用例](../../platform/src/test/java/dev/merchantpay/platform/ExportTest.java) |
| 完整后端回归 | 平台 240 项 + 模拟器 4 项，共 244 项通过，失败 / 错误 / 跳过均为 0；66 个 Java 文件格式检查通过 | [完整日志](../../.work/round4-fix-final-backend.log) |
| 部署后真实浏览器 | 新增管理员导出审计流程 1 条通过 | [日志](../../.work/round4-fix-browser-green.log)、[浏览器用例](../../console/tests/export-audit.spec.ts) |
| 构建与部署 | 构建及 Java 格式检查通过，已启动本地修复版 | [构建日志](../../.work/round4-fix-package.log)、[部署日志](../../.work/round4-fix-deploy.log) |
| 只读运行核对 | 本次部署后新增下载审计 1 条，商户缺失 / 错配均为 0；借贷差额 0，健康 UP | [运行日志](../../.work/round4-fix-runtime.log)、[只读探针](../../.work/Round4RuntimeProof.java) |
| 浏览器用例格式 | Prettier 检查通过 | [日志](../../.work/round4-fix-e2e-format-check.log) |
| 独立复核 | 未发现修复范围内新增的可执行问题；复核为源码 / 日志检查 | 本轮独立复核记录 |

初版测试误用了 AssertJ 对 `singleElement()` 的泛型断言，导致编译失败；改为对实际 Map 元素断言后才取得上述行为红灯，失败日志保留在 `.work/round4-fix-test-compile-failed.log`，未将其算作产品问题。

本次没有回填历史下载审计的空商户字段，也未删除历史记录；修复针对后续下载。前端业务源码未修改，25 项前端单测和旧的 5 条浏览器流程本轮未重跑，仍为上一轮历史基线；本轮实际执行新增管理员浏览器用例。性能、规模、长稳和漏洞状态未重新认证。

最终去凭证结果见 [round4-fix-verification.json](../releases/evidence/round4-fix-verification.json)。代码范围为一个下载控制器、现有导出测试和新增浏览器用例，文件摘要见 [source-digests](../../.work/round4-fix-source-digests.json)。
