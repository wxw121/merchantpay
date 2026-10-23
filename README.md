# MerchantPay · 商户支付与对账平台

MerchantPay（仓库名 `merchantpay`）面向具有交易或金融研发经验的 Java 工程师，建设可演示、可验证、可扩展的支付工程项目。项目在三个大版本内完成业务闭环、并发与故障恢复验证、亿级历史数据处理和运维交付。

当前阶段：**V1 可运行验收候选，版本 1.0.0-SNAPSHOT**。已实现支付、退款、复式账务、可靠任务、对账与工作台；300 完成 TPS、D1 全量数据与 R1 四小时长稳尚未通过，不标记正式发布。实际证据和未验收项见 [V1 交付记录](docs/releases/v1.0.0.md)。

2026-10-23完成后续优化交付：支付路径、任务队列隔离、配额SQL与准入锁改造的同资源对照为83.17→94.18→79.28完成TPS，候选仍有176次503。最终目标复测为52.12支付/16.95退款TPS、752.53查询QPS，仍有大量错误和尾延迟，未达标。344项后端测试、格式检查及273420条请求独立复算通过；连接池隔离无稳定收益，默认关闭。两套本地服务已更新，详见[支付路径优化记录](docs/quality/2026-10-22-flow-optimization.md)。V13升级必须先排空并停止所有旧API写入实例和worker，不能混合滚动。

2026-10-22上一轮并发优化与目标复测：修复渠道HTTP池默认5连接和后台固定30名额限制，同资源支付约48→94.5完成TPS，但峰值仍有503；零错误档支付58.5TPS、独立退款31.1TPS、独立查询599.8QPS。支付/退款受理尾延迟和完整300/30TPS、2000QPS目标仍未通过。后端303项回归通过，103万余条请求独立复算一致，详见[上一轮并发优化报告](docs/quality/2026-10-22-throughput-optimization.md)。后面的四小时证据属于旧构建。

2026-10-21 继续完成非发布验收：已实测30分钟混合、完整四小时负载、受限浏览器指标，以及20万笔历史支付的成套恢复和恢复后真实支付退款。第一轮四小时因321个入口容量503失败，保留原报告；正式R1/D1、安全与灾备门槛仍分别记录。最新结果见 [非发布剩余项验收](docs/quality/2026-10-20-v1-nonrelease.md)和[结构化证据](docs/releases/evidence/v1-local-remaining.json)，操作入口见 [本地交付手册](docs/releases/local-delivery.md)。

新增显式容器性能模式`start-containers.ps1 -Storage s3 -Performance`，数据库2GiB/缓存1GiB、API在途128；两轮尖峰、新30分钟混合及完整四小时复验通过，系统错误/拒绝/未完成均0。应用和身份服务均已核验JDK25.0.5，Prometheus3.13.5补丁已部署但仍有2条gRPC高危。普通启动默认不变；本机可执行收尾已完成，正式300完成TPS/R1/D1等门槛仍未通过。

## 本地启动（Windows）

准备 PowerShell 7、Maven 3.9+，并让 Docker Desktop Linux 引擎处于 Running。Java 25、Node 24 和 pnpm 安装到项目 `.tools`，不改变系统工具链。首次运行需要网络。

```powershell
# 首次获取项目；已有本地工作区可跳过这两行
git clone git@github.com:wxw121/merchantpay.git
Set-Location merchantpay
# 在项目根目录、pwsh 内执行
& scripts/bootstrap.ps1
# 大文件下载不稳定时可加 -UseRanges
& scripts/pnpm.ps1 -PnpmArguments @('install','--frozen-lockfile')
& scripts/start-local.ps1
```

打开 http://127.0.0.1:5173，工作台管理员账号为 `admin`，密码为 `admin`（仅供本地演示）。商户账号为 `merchant-a`、`merchant-b`，其他角色账号为 `finance`、`auditor`、`ops`；这些账号的密码为首次生成的 `.env` 中 `DEMO_PASSWORD`，请在本地编辑器查看。Keycloak 管理员 `local-admin` 使用独立的 `KEYCLOAK_ADMIN_PASSWORD`。初始化脚本不会覆盖已有 realm 的密码，旧演示 realm 缺邮箱时可执行 `scripts/sync-local-identity.ps1`。

业务健康检查 http://127.0.0.1:8080/actuator/health，Keycloak http://127.0.0.1:8180，独立模拟器 9091，MySQL 3307。所有演示服务只开放本机端口。收银演示为 http://127.0.0.1:5173/cashier。

创建 100.00 元支付并退 30.00 元，详情应有 2 张凭证、6 条分录，成功退款 3000 分、累计退费 18 分。对账页面可拉取当天渠道账单，导出页面生成并鉴权下载 ZIP。商户 B 无法读取 A 的交易或文件。

列表默认最近七天，可按北京时间选择最多 31 天的范围（开始日期含、截止日期不含）；切换条件会从第一页查询。平台商户选择器支持按名称或编号搜索，完整编号优先匹配，可访问首批 100 家之外的商户；支付、对账与导出表单共用此选择器。

```powershell
& scripts/verify.ps1                         # 后端含真实 MySQL 容器、前端测试及构建
& scripts/verify.ps1 -Browser                # 再验证 OIDC 与浏览器核心流程、响应丢失恢复
& scripts/verify.ps1 -Browser -Scale         # 再验十万行人工账单，最多等待 10 分钟
& scripts/restart-recovery.ps1               # 暂停本项目 worker，受理后重启并核验恢复
& scripts/api-example.ps1                    # 签名下单、查询，不输出 secret
& scripts/load/run-local.ps1 -Scenario pay -Rate 30 -SecondsOrPayments 30
& scripts/load/run-local.ps1 -Scenario query -Rate 300 -SecondsOrPayments 30
& scripts/load/run-local.ps1 -Scenario dataset -Rate 30 -SecondsOrPayments 1000 -RefundPercent 20
& scripts/load/test-local.ps1 -MySql         # 压测计量与真实事务可见性回归
& scripts/load/diagnose-local.ps1 -Seconds 45 # 另一个终端只读采集指标、锁等待、SQL 摘要
& scripts/stop-local.ps1                     # 停项目记录的进程，保留数据；加 -Infrastructure 同时停容器
```

需要固定资源对比时，先排空并停项目，再执行 `scripts/start-local.ps1 -Performance -SkipBuild`。`scripts/load/baseline-local.ps1 -CheckOnly`只读核验实际配置；不带CheckOnly会连续预热10分钟并测三轮。资源预算、恢复普通配置和资格条件见[D0性能基线](docs/quality/2026-10-19-d0-performance-baseline.md)，不替代R1/D1。

排查503与观察器开销可用 `scripts/load/baseline-local.ps1 -CalibrateObserver`：同样预热10分钟，固定四轮full/after/after/full。`after`只在发送结束后核验资金，完成TPS/延迟为null，不能获得基线资格；503固定原因头与服务端计数用于核对返回位置，见[口径与结果](docs/quality/2026-10-19-api-unavailability-observer.md)。

进一步采样已观测到binlog同步等待至少677ms；5000µs/32分组候选使拒绝和延迟恶化，已恢复原参数及监控。新增只读当前事件采样与回归，本轮14023笔受理全部过账且平衡；尚未通过300完成TPS，详见[提交等待取证](docs/quality/2026-10-19-binlog-commit-waits.md)。

随后完成[事务边界盘点](docs/quality/2026-10-19-transaction-census.md)与nonce提交分组ABA；高压确有分组，但未证明端到端收益，候选业务源码/JAR已撤回。日常诊断保留三项binlog缓存计数，不能当作fsync；累计新增20113笔支付、20笔退款全部过账且平衡，详见[对照与回退记录](docs/quality/2026-10-19-nonce-commit-batching.md)。

先启动本地服务再执行浏览器 / 负载 / 重启演练。原 `run-local.ps1` 生成器覆盖单商户/当前日期；新增 `run-v1.ps1` 支持多凭据、标准渠道和真实HTTP通知，另有 `d1-fixture.ps1` 隔离离线历史夹具。**完整D1仍未实装通过**，本机全量空间预检拒绝。报告分别位于 `.work/performance` 和 `.work/acceptance`，失败样本和受理交易保留。

压测计量 V2 使用独立连接观察已提交的成功状态、平衡凭证与 Outbox，逐业务号输出 `.requests.jsonl`；完成窗口及 P99 给出采样上下界，不再用凭证 `created_at` 代替提交时间。未完成请求另列等待年龄，可能使全体受理请求的 P99 上界为 `null`；退款 HTTP 不混入主阶段统计。诊断脚本只读取本地指标和 MySQL 规范化摘要，使用已有本地 root 凭据，不修改数据库配置；先启动诊断再在另一终端压测，并核对采样时间覆盖范围。结果与瓶颈见 [D0 计量与诊断](docs/quality/2026-10-19-d0-load-diagnosis.md)。

诊断还输出 `.io-before.json`、`.io-after.json`、`.io-delta.json`，包含缓存/日志计数、数据文件/redo/binlog读写与其他I/O计时、监控开关和配置快照。差值使用精确整数；缺失或未计时为null，配置/可见计数回退等异常使valid=false。先核对有效性，再分析等待；文件MISC含同步、打开和关闭等操作，不能直接当作fsync。命令与解释见[运行手册](docs/releases/runbook.md#观测与诊断)。

同一JAR的128MiB→1GiB→128MiB缓存探索中，较大缓存组完成约121–124 TPS，物理读和空闲页等待下降；提交成本仍在，三组均有503。实验后恢复128MiB，未永久调参或宣告300 TPS达标。具体条件、前置异常及后续顺序见[缓存与I/O测量](docs/quality/2026-10-19-mysql-io-cache.md)。

随后已修复模拟器健康统计/回调轮询的历史回执扫描，健康抽测7–32ms；诊断增加stageDeltas，未开启阶段为null，工具保持只读。临时提交计时证明仍需拆解提交等待，支付高压未达标；更新范围、备份及限制见[渠道扫描与提交阶段记录](docs/quality/2026-10-19-channel-health-commit-stage.md)。

签名API配额检查已采用10ms目标的小批聚合，MySQL提交后逐请求分配固定秒500次边界；多实例和重启共享额度，nonce仍先逐请求持久化。未完成数据库工作有独立容量限制，调用方超时不返还工作许可；未知结果返回503。实现、故障回归和同机对照见[配额热行优化](docs/quality/2026-10-19-rate-limit-batching.md)，不代表R1容量已通过。

新支付还有持久化积压背压：默认每个逻辑桶最多16个未完成支付，16桶合计上限256；单桶先满时会提前拒绝。`PAYMENT_MAX_INFLIGHT_PER_SHARD` 支持1–4096，所有实例应使用相同配置。容量满返回503和 `Retry-After: 1`，按原业务号、新nonce退避重试；未知结果和关闭处理中仍占容量，终态与账务同事务释放，幂等重试、查询、关闭及退款仍可继续。升级V12前必须停止所有旧版写入实例，不能与旧版混合滚动；历史超限工作保留排空。设计、验证和容量边界见[支付积压背压](docs/quality/2026-10-19-payment-backpressure.md)。

后台任务确认采用10ms目标的提交分组，逐条校验租约后整组提交，确认结束前继续占worker名额并保持心跳；渠道原始回执仍先独立落库，业务过账与渠道尝试最终状态改为同事务。没有新增表或提高后台并发，详见[后台提交优化](docs/quality/2026-10-19-task-commit-batching.md)。

默认商户 API 凭据首次写入 `.data/platform/local-api-credentials.json`，轮换后只显示一次新 secret，压测凭据文件需同步更新。`.env`、`.data`、`.tools`、日志与测试产物均忽略入库，不公开密码和原始凭据。

可选观测：`docker compose --env-file .env -f infra/compose.yaml --profile observability up -d prometheus`，打开 http://127.0.0.1:9090。指标认证使用用户名 `metrics` 及 `.env` 的 `METRICS_PASSWORD`，不能用于业务 API。

## 实现与运行边界

`platform` 是 Boot 4.1 / JDBC / Flyway / Modulith 模块化单体；`simulator` 有独立 H2 文件数据库；`console` 使用 React 19 / TypeScript 6 / Ant Design 6 / TanStack Query / ECharts。`infra` 提供基础设施，`scripts` 提供启动、验证和负载生成。

Java 25 正式特性落在虚拟线程、ScopedValue、record / sealed 与模式匹配；Boot 4 / Framework 7 使用 HTTP 客户端分组、原生 API 版本、Jackson 3 严格金额契约、独立观测安全链。未启用 preview。虚拟线程不扩大 JDBC 连接额度。

金额用分单位字符串，前端 BigInt 格式化；状态、凭证和 Outbox 同事务。支付、退款、关闭、账单登记与导出创建的操作人审计随相应业务事务提交。退款在原支付行预占额度，未知结果保留预占。来源事件唯一，借贷平衡；通知至少一次投递、按 eventId 去重。账单 500 行检查点、原始摘要证据、冻结快照；导出一致性快照和流式 ZIP 分片。

账单与导出支持local/S3，常驻部署仍默认local；真实S3 HTTP回归已运行。MySQL、模拟器文件、证据与MASTER_KEY需成套备份，新增本机独立容器加密备份/隔离恢复入口；跨主机与D1灾备未认证。详见 [运行手册](docs/releases/runbook.md)、[本地交付](docs/releases/local-delivery.md)、[商户 API 契约](docs/api/merchant-api.md)。代码仓库为 [wxw121/merchantpay](https://github.com/wxw121/merchantpay)，远程 CI 结果以该仓库 Actions 的实际运行记录为准；历史本地报告不代表远程 CI 通过。

## 阅读顺序

1. [需求说明书](docs/superpowers/specs/2026-10-17-merchant-payment-platform-design.md)：业务边界、角色、功能要求、状态与账务规则、验收标准。
2. [三个大版本规划](docs/roadmap/three-major-releases.md)：V1、V2、V3 的范围、里程碑、交付证据和发布门槛。
3. [技术基线与架构演进](docs/architecture/technology-baseline.md)：前后端技术、选型依据、数据流、一致性与扩展方案、官方资料。
4. [性能、容量与韧性验收](docs/quality/performance-and-resilience.md)：TPS/QPS 定义、环境、数据集、压测与故障演练口径。

## 三个版本的目标

| 版本 | 核心结果 | 支付完成吞吐目标 | 数据规模目标 |
| --- | --- | --- | --- |
| V1 | 支付、退款、基础账务、对账闭环；核心正确性成立 | 300 TPS | 100 万支付单、500 万分录、1,000 万事件 |
| V2 | 并发正确性、异步可靠性、高可用、差错审核与结算单 | 2,000 TPS | 1,000 万支付单、5,000 万分录、1 亿事件 |
| V3 | 分片、流批数据处理、历史查询、迁移与灾备、完整交付 | 5,000 TPS | 1 亿支付单、5 亿分录、10 亿事件 |

支付 TPS 指唯一支付单完成渠道成功确认且其账务事务已经提交，不以返回“处理中”或消息入队代替。完整口径和资源条件以性能验收文档为准。

## 产品定位

第一版采用人民币单币种、多商户、两个独立模拟支付渠道。三版内成熟的含义是：约定范围内的业务、账务、性能、数据规模、安全和运维门槛全部通过，并有可复现证据。

真实资金清算、真实银行出款和商业运营接入不属于本项目三版范围；真实渠道适配能力通过一个官方沙箱适配器及契约测试验证。官方沙箱与自建模拟渠道在界面、配置、数据和报告中明确标记。
