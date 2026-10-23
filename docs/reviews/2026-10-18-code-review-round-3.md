# 第三轮代码审查（2026-10-18）

**当前状态：以下四项均已有修复，原审查发现与失败证据保留；修复说明和验收证据见文末。**

本轮确认 **3 项新的 P2、1 项新的 P3**，未确认新的 P0 / P1。审查对象为第二轮四项问题修复后的当前源码，覆盖支付、退款、关闭、对账解析、导出、通知、签名与管理端状态同步，并由独立审查席位复核。前两轮已修复问题不重复计数。

审查阶段仅新增本报告和 `.work` 中的隔离验证程序、日志与截图，未修改业务源码或正式测试，未重启应用。后端复现使用独立 H2 数据库；浏览器使用真实前端与独立 Edge，全部 `/api/**` 请求均由桩响应拦截，没有业务请求到达运行中的后端。以下保留修复前发现，授权后的修改与实际部署见文末验收。

## 1. [P2] 支付、退款和关闭的审计写入不与业务提交保持原子性

- 位置：[ConsoleController.java:150–152](../../platform/src/main/java/dev/merchantpay/platform/web/ConsoleController.java#L150)，退款与关闭同样位于 [164 行](../../platform/src/main/java/dev/merchantpay/platform/web/ConsoleController.java#L164)、[176 行](../../platform/src/main/java/dev/merchantpay/platform/web/ConsoleController.java#L176)。审计插入为 [MerchantService.java:115](../../platform/src/main/java/dev/merchantpay/platform/merchant/MerchantService.java#L115)。
- 触发：业务服务已提交，随后 `audit_records` 插入失败。例如审计表约束、存储或数据库写入异常，而业务表仍可正常写入。
- 根因：控制器先调用带事务的业务服务，再单独调用 `merchants.audit`，外层没有把二者包含在同一事务内，也没有与业务同事务提交的审计待办。业务与渠道任务已持久化后，审计异常只能使当前请求失败，不能撤销之前的提交。
- 隔离复现：给私有 H2 的审计表添加拒绝正常操作的 CHECK 约束，通过真实控制器及领域服务分别发起支付、退款和关闭。三个请求均抛出 `DataIntegrityViolationException`，但结果如下：

| 操作 | 请求失败后的业务状态 | 对应 PENDING 渠道任务 | 审计记录 |
| --- | --- | ---: | ---: |
| 支付 | 支付已创建，PROCESSING | 1 | 0 |
| 退款 | 退款已创建，预占 1,000 分 | 1 | 0 |
| 关闭 | 支付已转为 CLOSE_PENDING | 1 | 0 |

- 影响：调用方看到失败，后台仍可继续执行操作，却没有持久化的操作人审计或自动补审计任务。调用方用原业务号重试可能补写审计，但不能依赖人工重试实现 NFR-13 要求的敏感操作审计。没有证明重复扣款、超退或账务不平。
- 同根路径：[OpenApiController.java:37](../../platform/src/main/java/dev/merchantpay/platform/web/OpenApiController.java#L37)、[62 行](../../platform/src/main/java/dev/merchantpay/platform/web/OpenApiController.java#L62) 也先提交支付 / 退款再写审计；对账提交、导出创建也存在同类调用顺序。本轮动态证据限于上述三条控制台核心操作，其他路径为静态确认，不另拆发现。第一轮已记录的 OpenAPI 关闭缺审计不重复计数。
- 建议：让敏感业务变更、渠道任务与操作人审计在同一事务提交；若审计异步持久化，应在业务事务中保存包含操作人、资源和 requestId 的可靠审计待办。不要仅吞掉审计异常。
- 回归要求：分别注入审计写入失败，验证业务与审计共同回滚，或业务提交后存在可恢复的审计待办；覆盖支付、退款、关闭及其他同根入口，并保留原有幂等行为。

## 2. [P2] 切换当前商户后，收银台仍使用旧商户或锁死空商户

- 位置：[Payments.tsx:57–58](../../console/src/pages/Payments.tsx#L57)，关联 [67 行](../../console/src/pages/Payments.tsx#L67)、[90 行](../../console/src/pages/Payments.tsx#L90) 和常驻收银表单 [817 行](../../console/src/pages/Payments.tsx#L817)。
- 触发路径一：管理员在顶栏选择商户 A，进入收银台，再将顶栏改为 B。顶栏显示 B，表单仍显示 A 且不可编辑；填写金额提交后，请求实际仍为 `POST /api/console/payments?merchantId=review-a`。
- 触发路径二：以“全部商户”进入收银台，再从顶栏选择 A。表单商户仍为空，却变为禁用；提交只提示“请选择商户”，用户无法在当前表单继续下单。
- 根因：`initialValues` 只初始化 Ant Design Form，后续 Workspace 的 `merchant` 变化不会同步字段；`disabled={!!merchant}` 却会立即跟随新上下文变化。收银台的 `PaymentForm` 没有按商户重建或显式同步商户字段，提交使用的是旧的 `values.merchantId`。
- 影响：当前商户与实际下单目标不一致，或正常下单被阻塞。这是管理员工作台的状态错误，不是租户权限绕过；旧商户仍显示在表单中，不能描述成完全不可见的目标替换。
- 隔离验证：真实页面的两条路径均复现。第一条捕获到旧商户 POST；第二条断言表单为空且禁用、出现必填错误、POST 数为 0。所有请求均被浏览器桩拦截，没有创建真实订单。
- 建议：商户上下文变化时同步表单商户，或明确重建未提交的表单；妥善处理已填内容和已生成支付结果，避免无提示丢失。保证顶栏、表单及实际请求使用一致的商户。
- 回归要求：覆盖全部商户 → A、A → B、A → 全部商户，断言字段值、可编辑状态与请求目标一致。支付 / 对账等模态框有遮罩，本轮未将收银台复现结果扩展为所有模态框均可触发。

## 3. [P2] 账单解析静默删除字段内部回车，改写后的流水号可通过对账

- 位置：[ReconciliationService.java:356](../../platform/src/main/java/dev/merchantpay/platform/reconciliation/ReconciliationService.java#L356)。字段校验发生在该读取函数返回之后。
- 触发：账单摘要、元数据、笔数和金额均正确，但业务字段中夹有 `\r`。当前读取器为兼容 CRLF，删除所有回车字符，而不仅处理行尾的 CRLF。
- 隔离复现：先生成一笔成功支付，其渠道流水为 `channel-carriage-return`。账单中填写不同的原始流水 `channel-carriage-\rreturn`，保留合法 CRLF 行尾并按原文件计算正确摘要。实际处理结果为 COMPLETE、差错 0；解析后的 `reference` 被改写成本地支付的合法流水，`raw_line` 也不再保留字段内部的回车。
- 影响：原本应按格式校验拒绝的账单内容，被转换成另一个有效业务标识并判定匹配；存入行表的“原始行”也与原字段不一致，影响 FR-20 异常文件隔离和 FR-21 原始行追溯。原始账单文件完整保留，账务数据未因此被改写，不能称为证据全部丢失或资金损失。
- 建议：仅按账单格式识别合法行结束符，字段内部控制字符应原样交给校验并拒绝，或直接作为永久格式错误隔离。处理不支持的孤立 CR 时应明确报错，保留原始文件；继续维持单行 1,024 字符上限，避免换成无界整行读取。
- 回归要求：合法 LF / CRLF、业务号 / 流水号 / 金额 / 元数据内嵌 CR、孤立 CR 与超长行；验证错误输入隔离、原文保留、不会被清洗为可匹配记录。

## 4. [P3] 取消账单上传后，空文件输入仍会提交上一次选择的文件

- 位置：[Reconciliation.tsx:143–145](../../console/src/pages/Reconciliation.tsx#L143)，关联父组件文件状态 [53 行](../../console/src/pages/Reconciliation.tsx#L53)、校验 [164 行](../../console/src/pages/Reconciliation.tsx#L164) 和 FormData 写入 [175 行](../../console/src/pages/Reconciliation.tsx#L175)。
- 触发：打开上传，选择文件 A，取消，再重新打开。文件输入已显示“未选择文件”，此时直接点击“上传并开始校验”。
- 根因：`destroyOnHidden` 销毁了文件输入，但取消仅执行 `setOpen(false)`，父组件中的 `file` 未清空。新弹窗的校验和上传继续读取残留的 `File`，开启弹窗和成功关闭同样未统一重置。
- 隔离验证：第一次选择后输入的 `files.length=1`；取消重开后为 0、文件名为空；随后捕获的 multipart 仍包含 `cancelled-review.csv` 及其原始标记正文，并为旧文件计算摘要。
- 影响：用户可以在没有可见文件选择的情况下误提交旧账单。后端仍有摘要与格式校验，本轮也没有向真实后端发送文件，因此不宣称已经创建错误批次或发生跨商户数据泄露。
- 建议：开启新的上传、取消及成功关闭时统一清空文件状态，使输入显示与实际提交一致；未选择文件时必须阻止提交。
- 回归要求：选择 → 取消 → 重开 → 不选文件提交，以及成功上传后重开；断言无残留文件和上传请求。

## 审查阶段证据与验证边界

| 检查 | 本轮结果 | 证据 |
| --- | --- | --- |
| 后端核心回归 | 63 项通过：ChannelEvidence 31、DurableTask 5、FinancialCore 15、Reconciliation 12；失败 / 错误 / 跳过均为 0 | [执行日志](../../.work/round3-core-regression.log) |
| Java 格式检查 | `fmt:check` 通过，58 个文件、0 个不合规 | 同上 |
| H2 隔离缺陷复现 | 三种审计失败 + 一种字段内部 CR，共四个场景，缺陷断言均成立，程序退出 0 | [源码](../../.work/dev/merchantpay/platform/ThirdReviewProof.java)、[日志](../../.work/third-review-reproductions.log) |
| 浏览器隔离复现 | 收银台两条路径、取消上传重开路径均复现，最终探针退出 0，页面异常数组为空 | [源码](../../.work/round3-front-state-proof.mjs)、[JSON](../../.work/round3-front-state-proof.json)、[日志](../../.work/round3-front-state-proof.log) |
| 浏览器截图 | 顶栏 B / 表单 A；上传重开后未选择文件 | [商户切换](../../.work/round3-merchant-switch.png)、[取消重开](../../.work/round3-cancelled-upload.png) |
| 独立复核 | 指定后端范围未确认其他新增 P1 / P2；两项前端问题补齐真实页面证据 | 本轮审查记录与上述浏览器产物 |
| 全量后端、前端、真实浏览器回归 | 本轮没有重跑；上一轮 186 项后端、18 项前端、5 条浏览器流程仅作为历史基线 | [上一轮验收证据](../releases/evidence/round2-fixes-verification.json) |
| MySQL 专项、R1 / D1、四小时长稳与依赖审计 | 本轮没有重新认证，不从 H2 或模拟浏览器证据推导吞吐 / 规模结论 | 原发布门槛保持 |

核心回归执行：

```powershell
& scripts/mvn.ps1 -MavenArguments @('-pl', 'platform', '-Dtest=FinancialCoreTest,ReconciliationTest,ChannelEvidenceTest,DurableTaskTest', 'test', 'fmt:check', '-B', '-ntp')
```

隔离探针不属于正式测试新增覆盖。Java 探针先编译后按类名运行，避免与包内测试夹具产生不同类加载器；浏览器初次启动和早期选择器失败属于验证工具问题，修正后完成复现，未将其列为产品缺陷。

## 已考虑但未另列的新缺陷

- 前两轮的十项已修复问题不重复计数；原 OpenAPI 关闭缺审计的低优先级记录也不另计。
- 退款根支付锁、额度预占及渠道证据事务未确认新的超退或重复入账路径；不能由本轮审计遗漏推导资金丢失。
- 通知至少一次投递、12 次自动重试后 DEAD、授权重放及当前快照通知遵循现有契约，未证明新的可靠性缺陷。
- 导出文件清理和有效期在运行手册已有说明；未证明失租结果可发布或跨身份下载。公式注入的 Unicode 变体没有取得可执行的表格证据，不列确定缺陷。
- OpenAPI 契约明确按 key 限流，多密钥不是已证明的商户额度绕过；通知发送还有地址过滤和禁止跳转，静态 URL 验证本身不能证明 SSRF 绕过。
- 商户列表先取前 100 家再按当前商户过滤，可能出现空列表，但有搜索恢复路径，且接近前轮已处理范围，不作为本轮独立发现。
- 对账快照的 MySQL 锁成本、模拟渠道大账单内存成本及混合负载影响没有本轮实测；不将潜在容量风险直接定性为已经发生的规模故障。
- 本地 `admin/admin`、模拟渠道、当前文件存储、细分角色、会话撤销、正式沙箱及 V2 审核结算，沿用用户授权和既定版本范围。

修复前建议优先处理三项 P2，再完成上传状态修复。当时已有 63 项核心回归通过，不代表这些新增边界已被覆盖，也不构成全量发布或高并发认证。

## 修复验收（2026-10-18）

用户已授权修复上述四项。修复复用现有事务、表单和有界读取机制，没有新增依赖或数据库迁移，版本保持 `1.0.0-SNAPSHOT`。

| 原问题 | 修复行为 | 新增覆盖 |
| --- | --- | --- |
| 敏感操作与审计分开提交 | 控制台和 OpenAPI 的支付、退款、关闭，以及导出创建，在同一事务完成业务、任务与审计；签名关闭同时补齐 PAYMENT_CLOSE 审计。账单先在事务外传输和保存文件，再在登记事务内写审计，重复批次路径也执行审计 | 9 类入口 × H2 / MySQL，注入审计写入失败，验证业务 / 任务回滚、退款预占不增加、关闭状态不变，恢复后用相同请求成功重试；验证操作人、requestId、资源及对应任务，并断言文件传输期间无数据库事务 |
| 收银台商户上下文过期 | 仅同步表单 merchantId，切回全部商户时清空并恢复可编辑；保留业务号、金额、渠道、场景及已生成的支付结果 | 全部 → A、A → B、A → 全部、手动选择及实际请求目标；已生成结果不随顶栏切换清空 |
| 字段内部 CR 被删除 | 读取器仅接受 LF / CRLF，其他 CR 永久隔离；保留原文件、无末尾结束符支持及 1,024 字符上限 | 新增 14 个解析场景：8 种非法 CR、4 种合法结束符组合、2 个行长边界；随既有 MySQL 继承测试执行 |
| 上传取消后复用旧文件 | 新开、取消、成功关闭均清空文件；输入随开关重建，防止关闭动画期间快速重开产生显示 / 状态不一致；摘要读取和提交期间禁止关闭或替换文件 | 取消 / 成功后重开需重新选择；读取中保留可见文件；真实页面验证空选择不发请求，新文件内容不含旧文件 |

独立复核未发现修复范围内新增的 P1 / P2 / P3。该复核是源码与测试审查，没有替代下面的实际运行验证。

| 验证 | 结果 | 证据 |
| --- | --- | --- |
| 审计跨数据库专项 | H2 9 项 + MySQL 9 项通过，格式检查通过 | [日志](../../.work/round3-fixes-audit-cross-database.log)、[测试](../../platform/src/test/java/dev/merchantpay/platform/AuditTransactionTest.java)、[MySQL 入口](../../platform/src/test/java/dev/merchantpay/platform/MySqlAuditTransactionTest.java) |
| 完整后端测试 | 平台 228 项 + 模拟器 4 项，共 232 项通过，失败 / 错误 / 跳过均为 0 | [平台测试日志](../../.work/round3-fixes-final-backend.log)、[模拟器日志](../../.work/round3-fixes-final-simulator.log) |
| 后端构建及格式 | 停止占用 JAR 的项目进程后重新打包通过；平台 60 个、模拟器 6 个 Java 文件全部合规 | [构建 / 格式日志](../../.work/round3-fixes-final-package.log) |
| 前端完整单测 | 25 项通过 | [日志](../../.work/round3-fixes-front-green.log)、[支付表单测试](../../console/src/pages/Payments.test.tsx)、[上传状态测试](../../console/src/pages/Reconciliation.test.tsx) |
| TypeScript / 前端构建与格式 | 构建和 Prettier 检查通过；原整包体积提示保留 | [构建日志](../../.work/round3-fixes-front-build.log)、[格式日志](../../.work/round3-fixes-front-format-check.log) |
| 隔离 Edge 状态验证 | 商户选择三种转换、空文件阻止、重新选择文件均通过，脚本异常 0；全部 API 被拦截，真实后端业务写入 0 | [源码](../../.work/round3-fixes-front-proof.mjs)、[结果](../../.work/round3-fixes-front-proof.json)、[日志](../../.work/round3-fixes-front-proof.log)、[商户截图](../../.work/round3-fixes-merchant-switch.png)、[文件截图](../../.work/round3-fixes-cancelled-upload.png) |
| 本地部署与真实浏览器 | 修复包已启动，既有 5 条真实流程通过：详情分页、响应丢失 / 关闭、OIDC 支付退款对账导出、跨商户拒绝、管理员操作；十万行用例按原配置跳过 | [部署日志](../../.work/round3-fixes-final-deploy.log)、[浏览器日志](../../.work/round3-fixes-final-e2e.log) |
| 只读运行核对 / Compose | 健康 UP，迁移仍为 V11；借贷差额、不平凭证、非法退款额度、本轮新支付 / 退款缺失创建审计数全部为 0；Compose 配置验证通过 | [运行日志](../../.work/round3-fixes-runtime.log)、[探针](../../.work/Round3RuntimeProof.java) |

失败证据未被最终通过覆盖：新增后端测试修复前为 35 项中 17 项失败、0 错误（9 个审计入口、8 个非法 CR），见[红灯日志](../../.work/round3-fixes-backend-red.log)。前端修复前为 6 项失败、19 项通过，见[前端红灯](../../.work/round3-fixes-front-red.log)。

验证过程中修正了测试工具问题：Spring 7 的 multipart builder 继承关系、H2 / MySQL 的约束异常映射及约束名大小写、jsdom 弹窗动画等待、隔离 Edge 探针强制禁用 CSS 动画导致加载图标未消失。相关失败分别保留在 `.work/round3-fixes-audit-test-compile-failed.log`、`.work/round3-fixes-mysql-test-assumption-failed.log`、`.work/round3-fixes-h2-test-name-case-failed.log`、`.work/round3-fixes-front-proof-early-timeout.log` 和 `.work/round3-fixes-front-proof-motion-failed.log`；未将这些测试或探针问题当成新增产品缺陷。自动审批还曾因额度不足无法完成审核，未执行当时请求的命令；额度恢复后重新审核再继续验证，没有绕过审批。

平台 228 项测试通过后，同一次 `verify` 在 Boot 重打包阶段因 Windows 中运行的 JAR 文件占用而退出 1，原始日志保留，未伪称该命令整体成功。随后停止本项目进程，补跑模拟器 4 项测试，再用 `package -DskipTests fmt:check` 成功打包、检查格式并部署；平台测试已全绿且业务源码未再变更，因此没有重复执行耗时的 MySQL 全量测试。

最终去凭证汇总见 [round3-fixes-verification.json](../releases/evidence/round3-fixes-verification.json)，变更源文件摘要见 [source-digests](../../.work/round3-fixes-source-digests.json)。本轮不重新认证 R1 / D1、十万行整批规模、四小时长稳或依赖漏洞状态；整包体积提示仍在。
