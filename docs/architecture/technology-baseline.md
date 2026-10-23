# 技术基线与架构演进

核对日期：**2026-10-17**；V1 实现更新于 **2026-10-18**。本文保留目标技术决策；实际锁定版本与验证状态见 [V1 交付记录](../releases/v1.0.0.md)。V1 本地使用文件存储、手写类型客户端和 Java 负载生成器，S3、OpenAPI 生成客户端、k6 与完整观测栈尚未接入，不把计划项当成已实现。

关联：[需求说明书](../superpowers/specs/2026-10-17-merchant-payment-platform-design.md)、[版本规划](../roadmap/three-major-releases.md)、[验收口径](../quality/performance-and-resilience.md)。

## 1. 选型原则

采用当前稳定、可验证且与目标问题相关的技术。使用新一代 Java、Spring 和前端工具链；在可靠事件、大数据及实时计算出现实际需求的版本引入对应组件。核心资金路径不采用预览语言特性、里程碑依赖或缺少恢复依据的双写。

所有具体补丁版本在实现前通过兼容测试后写入 Maven Wrapper／BOM、前端锁文件与镜像清单，镜像记录 digest。表中的 `x` 表示选定维护分支内的安全补丁策略，不代表部署时使用浮动 `latest`。

## 2. 前端基线

| 层次 | 选型 | 引入版本 | 用途与限制 |
| --- | --- | --- | --- |
| 运行与构建 | Node.js 24 LTS、pnpm、Vite 8.3.x | V1 | 锁定构建环境和依赖；管理端采用 SPA |
| UI 框架 | React 19.3.x、TypeScript 6.0.x | V1 | 严格类型、路由懒加载、清晰的异步状态 |
| 组件与主题 | Ant Design 6.x、CSS Modules 和设计令牌 | V1 | 表单、表格、审批、日期金额展示及一致权限交互 |
| 请求与服务端状态 | TanStack Query 5.x、OpenAPI 生成客户端 | V1 | 请求缓存、失效、分页；支付写操作不做无业务键的自动重试 |
| 路由 | React Router 稳定 GA 分支 | V1 | 权限路由与错误边界；服务端仍执行全部授权 |
| 图表 | Apache ECharts 6.x | V1 基础；V3 完整 | 渠道成功率、时延、金额与完整性水位 |
| 实时交互 | SSE + 断线回查 | V2 | 任务与指标推送；不承担支付最终事实判定 |
| 质量 | Vitest、Testing Library、Playwright | V1 | 金额输入、状态展示、权限与完整业务流 |

前端使用服务端分页和聚合，按可见行渲染大表。金额接口使用字符串，展示通过明确的分单位格式化；禁止用 JavaScript `Number` 累计账务金额。审批与导出显示对象版本、数据水位和模拟环境标识。

不采用 SSR 作为该管理平台的默认方案：主要用户在登录后操作，需求重点是交互与数据权限。组件数量、动效或构建速度不能替代业务性能验收。

官方依据：React 已发布 [19.3](https://react.dev/blog/2026/09/09/react-19-3)；TypeScript 提供 [6.0 发布说明](https://www.typescriptlang.org/docs/handbook/release-notes/typescript-6-0.html)；Vite 的[维护版本页](https://vite.dev/releases)列出 8.3；Ant Design 的 [v6 迁移说明](https://ant.design/docs/react/migration-v6/)说明 React 与 CSS 变量要求。Node 的 LTS 状态以[官方发布页](https://nodejs.org/en/about/previous-releases)为依据；图表基线参考 [ECharts 6 官方说明](https://echarts.apache.org/handbook/en/basics/release-note/v6-feature/)。

## 3. 后端与运行平台基线

| 层次 | 选型 | 引入版本 | 用途与限制 |
| --- | --- | --- | --- |
| 业务 JDK | OpenJDK 25 LTS，优先 Temurin 发行版 | V1 | 使用正式语言特性与虚拟线程；不打开 preview |
| 应用框架 | Spring Boot 4.1.x、Spring Framework 7，由 BOM 管理 | V1 | Spring MVC、校验、事务、配置与可观测性 |
| 模块边界 | Spring Modulith 2.1.x | V1 | 包边界与模块集成测试；不再并行建立第二套事件持久化机制 |
| 数据访问 | Spring JDBC／JdbcClient、显式 SQL、Flyway | V1 | 直接表达条件更新、锁、唯一性和批处理 |
| 权威存储 | MySQL 8.4 LTS、InnoDB | V1 | 支付、退款、凭证、分录、Outbox、审批与来源分配 |
| 身份与权限 | Spring Security BOM 版本、Keycloak 维护期 GA | V1；MFA V2 | OIDC、RBAC、租户约束；商户 API 独立签名 |
| 缓存与配额 | Redis 8.x | V2 | 读缓存、限额；业务写正确性不依赖缓存存活 |
| 消息通道 | Apache Kafka 4.3.x、KRaft | V2 | 分区、重放、消费积压和扩展；客户端优先跟随 Spring BOM |
| 事件发布 | Debezium 3.x + Kafka Connect，正式维护分支 | V2 | 捕获 Outbox 的已提交事件；精确补丁取决于驱动兼容准入 |
| 对象存储 | S3 兼容接口，开发环境使用兼容实现 | V1 | 原始账单、导出、备份和归档；配置不绑定单一厂商 |
| 可观测性 | OpenTelemetry、Prometheus、Grafana、Loki／Tempo | V1 基础；V2 完整 | 交易追踪、任务水位、错误与账务约束告警 |
| 测试 | JUnit、Testcontainers、属性测试、k6、Playwright | V1 | 真实中间件集成、并发不变量、负载与端到端验证 |
| 部署 | Docker Compose；V3 提供 Kubernetes + Helm | V1／V3 | 本地可启动；分布式实验有资源配额、健康检查与滚动发布 |

Java 25 在 Oracle 的[支持路线图](https://www.oracle.com/java/technologies/java-se-support-roadmap.html)中列为 LTS。Spring Boot 的[系统要求](https://docs.spring.io/spring-boot/system-requirements.html)覆盖该 JDK；[4.1 发布公告](https://spring.io/blog/2026/06/10/spring-boot-4/)确认该稳定版本线。Modulith 使用[官方 2.1 文档](https://docs.spring.io/spring-modulith/reference/index.html)作为基线。Redis 8 的可用性依据[官方 GA 公告](https://redis.io/blog/redis-8-ga/)，Kafka 依据[官方下载与维护分支](https://kafka.apache.org/community/downloads/)。

虚拟线程只用于合适的阻塞 I/O，并不扩大数据库连接数或外部渠道配额。必须对 JDBC 连接、渠道调用、待处理请求和重试队列设置上限，测量线程阻塞、连接等待与 CPU；不能以“启用虚拟线程”宣称已经解决高并发。

## 4. 大数据与实时链路

| 组件 | 基线 | 引入版本 | 具体职责 |
| --- | --- | --- | --- |
| Kafka | 4.3.x | V2 | 业务事件传递和短期恢复；生产目标副本数 3、`acks=all`、最小同步副本 2 |
| Debezium | 3.x 正式维护分支 | V2 | 捕获权威 Outbox；变更日志积压和位点必须可观察 |
| ClickHouse | 26.8 LTS | V2 投影；V3 规模化 | 交易明细、渠道分析、报表、历史检索；不是权威账簿 |
| Flink | 2.3.x，独立 JDK 17 运行环境 | V3 | 实时指标、预对账、乱序与迟到修正、批量重算 |
| Parquet + S3 | 兼容版本锁定 | V3 | 原始事实、账单和历史归档；带清单、摘要和版本 |

Flink 的[官方下载页](https://flink.apache.org/downloads/)列出 2.3 稳定线；[Java 兼容文档](https://nightlies.apache.org/flink/flink-docs-stable/docs/deployment/java_compatibility/)以 Java 17 作为推荐基线，因此本项目让 Flink 与 Java 25 业务应用分别打包和构建。跨边界使用版本化 JSON 契约，不直接复用用 Java 25 编译的领域 JAR。

ClickHouse 26.8 为[官方 LTS 版本](https://clickhouse.com/blog/clickHouse-release-26-08)。具体补丁遵循该分支安全更新，并在压测报告中固定。

前沿能力落在虚拟线程、稳定模块边界、CDC、流式处理、列式分析和可观测性上。三版范围内不增加缺少业务验收目标的 AI 写账、动态通用工作流或通用分库分表框架。

## 5. 逻辑架构与部署演进

### 5.1 V1

管理端／商户 API → 业务应用〔接入、交易、退款、账务、对账、通知〕 → MySQL。

独立渠道模拟器与业务应用通过 HTTP 交互；模拟器有独立状态存储，不能通过共享事务掩盖网络不确定性。Outbox 发布与后台任务先使用持久化轮询，原始账单和导出写对象存储。

同一交易根的状态、预占、凭证与事件本地提交。Spring Modulith 用于边界验证；可靠事件统一由业务 Outbox 承担。

### 5.2 V2

业务应用横向扩容；通知、对账、导出及读模型处理采用独立进程与配额。Outbox → Debezium／Kafka Connect → Kafka → 幂等消费者 → 通知、分析投影及任务。

支付与账务不因拆进程而变为两个独立写数据库的服务。财务后台、管理读服务可以独立部署，但资金事实写入仍经过交易核心事务入口。

### 5.3 V3

交易核心按支付根分片；Kafka 下游增加 Flink，输出可重建分析投影；ClickHouse 和 S3 承担分析与历史数据。对账任务按渠道、业务日、业务键区段并行。

分片事务仍在单个 MySQL 分片中完成。跨分片商户汇总与结算通过带水位和来源分配的可恢复任务聚合，不能把网络调用写成一个虚构的本地事务。

## 6. 正确性与可靠性方案

### 6.1 支付与回调

1. 对商户业务键执行稳定路由，校验参数摘要，持久化支付受理与待发渠道任务。
2. 渠道工作进程使用已保存的渠道尝试 ID 和幂等键调用渠道。超时保留未知，查询和重试不更换该身份。
3. 渠道回调先完成验签和关联校验，并将通知保存至持久化 Inbox 后才确认接收；业务执行可以异步继续。
4. 回调、主动查询、人工授权查证统一经过状态规则；成功事务同时写支付状态、凭证、分录与 Outbox。
5. 处理失败可重试；超过自动重试阈值后隔离并告警，但不得把资金未知事实自动改成失败。

Stripe 的[Webhook 说明](https://docs.stripe.com/webhooks)明确了重复和乱序风险；其[幂等请求说明](https://docs.stripe.com/api/idempotent_requests)也说明了渠道幂等的边界。项目独立保存业务唯一性，不假设渠道替平台永久去重。

官方联调选择支付宝开放平台沙箱，账号、签名、异步通知和可调用产品以[官方支持中心](https://open.alipay.com/support/supportCenter.htm)及当前控制台为准。Stripe 文档用于说明通用工程问题，不是支付宝接口字段或通知格式的替代文档。

### 6.2 退款

以原支付为加锁和额度边界。受理时在事务中预占；成功事务结转预占、累计成功金额及手续费，并写账。重复通知不重复结转；明确失败才释放。时间窗口到期、连接断开或重试次数耗尽都不是释放依据。

### 6.3 事件发布与消费

Outbox 保存稳定事件 ID 与聚合版本。V1 的发布租约只用于协调工作进程，数据库唯一键和消费幂等承担正确性。V2 切换 CDC 时保留高水位和重叠去重机制。

Kafka 消费者在目标事务完成之后提交位点。涉及 MySQL 的消费，将业务变更和消费去重记录放在同一个目标本地事务；外部通知保留稳定通知号、投递记录和收件方幂等建议。

Kafka 自身事务边界不等于外部支付和数据库的全局原子性。参考[Kafka 设计文档](https://kafka.apache.org/41/design/design/)及 [Debezium Outbox 路由说明](https://debezium.io/documentation/reference/stable/transformations/outbox-event-router.html)。

### 6.4 账务与商户热点

分录按业务事实追加，商户级实时汇总由下游投影产生。支付不逐笔争抢同一个商户累计余额行。退款控制的是原支付可退额度；结算控制的是来源金额分配，而不是无条件信任缓存中的可用余额。

凭证按事件唯一、分录模板校验借贷平衡，并和业务状态原子提交；定期对账务做独立重算与校验。高并发优化不能取消这些校验或分离它们的事务。

### 6.5 OLAP 去重和重算

ClickHouse 的 `ReplacingMergeTree` 后台合并不能证明实时去重。需要按稳定键和版本查询最新逻辑行；小范围明细可使用 `FINAL`／`argMax` 等经过验证的查询方式。不能把可能重复的原始插入直接送入永久累加金额的物化视图。

实时指标保存“维度 + 窗口 + 版本”的绝对结果快照，由查询层选择有效版本。Flink 在在线 30 天事件窗口内维护业务去重与状态；超过窗口的历史事件进入迟到／重算通道，不能重新当成新交易累加。

全量重放写入新的 `dataset_generation`，完成相同水位下的核对后再切换读指针；旧代和新代不能在同一汇总中相加。所有正式结算仍使用 MySQL 权威账务及归档校验事实。

官方说明：[ClickHouse CDC 与去重模型](https://clickhouse.com/blog/consuming-delta-lake-change-data-feed-cdc)、[重放结果去重实践](https://clickhouse.com/resources/engineering/building-report-results-and-run-history-with-clickhouse-cloud)。

## 7. 分片与迁移

### 7.1 路由与共置

- 固定 16 个逻辑桶，V3 映射到 4 个物理分片；V1／V2 可全部映射到同一个物理库，保持同一路由语义。
- 路由依据为规范化的 `merchant_id + merchant_order_no` 的稳定 SHA-256 摘要；算法版本固定，同一业务键不能因请求随机数路由变化。
- 支付号编码逻辑桶并带校验信息；退款、渠道尝试、凭证、分录、Inbox 业务处理与 Outbox 跟随原支付桶。
- 商户可跨支付根分布，避免把最大商户全部压在单个物理库。单个支付的退款仍有串行化上限，超额请求按规则排队或限流。
- 跨商户列表通过查询投影处理，精确业务 ID 查询直接路由；创建支付时的业务唯一约束在稳定桶中有效。
- 完整历史明细归档后，精简支付控制记录仍在线，保留实付、累计退款、有效预占、费率快照和账务来源；历史退款使用同一控制记录和分片事务，不能从 ClickHouse 汇总倒推额度。
- 实现固定路由和有界迁移能力，不另建一个通用 SQL 解析与分布式事务中间件。

### 7.2 搬迁规则

先快照与增量追平，再短暂停写指定桶、排空在途事务、校验行数／业务键／金额及事件水位，最后切换拥有者与路由 epoch。旧拥有者必须通过 fencing 拒绝旧 epoch 写入；故障时保持冻结或回到已验证拥有者。

不进行无原子依据的跨库双写。迁移期间的同业务号重试、迟到通知与历史退款必须有专项测试。迁移暂停时间单独测量，不能伪称任意规模零停机。

### 7.3 分区约束

支付单及业务去重索引不直接按日期分区而削弱唯一性。MySQL 要求分区表达式中的列包含于每个唯一键；若把业务唯一键改成“业务号 + 日期”，同业务号跨日期可能重复收费。因此三版基线使用逻辑分片加受控归档，账单中间数据与 OLAP 可按业务日分区。

依据：[MySQL 分区键与唯一键约束](https://dev.mysql.com/doc/refman/8.4/en/partitioning-limitations-partitioning-keys-unique-keys.html)。

## 8. 高可用、恢复与保留

V2 起性能参考环境采用每个物理分片三成员、单主的 MySQL InnoDB Cluster／Group Replication。耐久基线为 `innodb_flush_log_at_trx_commit=1`、`sync_binlog=1`；所有成员持久化配置 `BEFORE_ON_PRIMARY_FAILOVER`，关键支付／退款／账务写会话使用 `group_replication_consistency=AFTER`，确认前等待当前组成员应用完成。连接池必须保证会话设置正确隔离。

这些选择有复制与等待成本，吞吐测试必须保留相同耐久配置，不允许改成弱确认之后仍报告原 RPO 目标。切换后追平、异常等待超时和旧写入口 fencing 均需演练；失去多数派时停止该分片写入，禁止为了可用性强行双主。配置依据：[MySQL 事务一致性保证](https://dev.mysql.com/doc/refman/8.4/en/group-replication-configuring-consistency-guarantees.html)与 [InnoDB 耐久模型](https://dev.mysql.com/doc/refman/8.4/en/mysql-acid.html)。

Kafka 使用三副本与最小同步副本限制，CDC 位点和 binlog 保留覆盖约定恢复窗口；binlog 至少保留 72 小时，并监控磁盘与 CDC 最老未处理位置。Redis 不可用时保留 MySQL 正确性，缓存读可受控回源，配额采用保守策略，避免无限回源。

数据保留：

| 数据 | 策略 |
| --- | --- |
| 权威支付／账务 | 热数据 30 天；温数据至 180 天；冷归档至 365 天 |
| 未决支付／退款／差错 | 不因年龄归档出在线处理范围，直至查证或处理结束 |
| 业务幂等、支付控制记录与路由索引 | 至少覆盖 365 天演示保留期，归档后继续可判重并控制退款额度 |
| Kafka 事件 | 正常保留至少 7 天；超出通过已校验归档重放 |
| 原始渠道证据、账单、审批与审计 | 项目基线 365 天，受对象权限、摘要及版本控制 |
| 对账临时表与中间文件 | 封版并核对后 7 天可清理；不删除正式证据 |
| 导出文件 | 默认 24 小时过期，下载链接最多 15 分钟并校验权限 |

上述保留期是项目实验策略，不代替任何真实运营场景的法定保留要求。

## 9. 兼容准入与版本冻结

| 组合 | 必须执行的准入检查 | 不通过时的固定处理原则 |
| --- | --- | --- |
| Java 25 + Boot 4.1 + Modulith 2.1 | 构建、事务、模块测试、虚拟线程负载、JDBC 驱动测试 | 保持业务 JDK 和 Boot 主线，选择该维护线已修复补丁；不混用不兼容里程碑 |
| React 19.3 + TS 6 + Vite 8.3 + AntD 6 | 严格类型构建、组件交互、路由和 Playwright 核心流程 | 锁定被验证的稳定补丁组合，禁止强行忽略类型和 peer 依赖 |
| Kafka 4.3 broker + Spring BOM 客户端 | 生产／消费／重放、故障恢复、消息版本与认证 | 客户端独立兼容验证后才升级，不随意覆盖整套 Spring BOM |
| Debezium + MySQL 8.4 + Kafka Connect | 快照／增量、DDL、位点恢复、Outbox 路由、重复事件 | 采用官方支持矩阵内的维护补丁；不跳过恢复测试 |
| Flink 2.3 + JDK 17 + Kafka／ClickHouse 连接器 | 序列化、checkpoint／savepoint、重启、批量写和重放核对 | 独立锁定数据平台依赖，不继承业务 Java 25 构建目标 |
| ClickHouse 26.8 + JDBC／HTTP 驱动 | 金额精度、幂等、版本合并、查询与重算 | 维持权威 OLTP 不变，修正投影写法后重新核对 |

实现前记录完整依赖组合并执行这些检查，是交付工作的一部分。当前文档只核对官方版本与能力边界，没有将尚未运行的组合描述为“已兼容”。

## 10. 本项目决策与官方事实的区别

官方资料支持版本可用性、技术边界和约束；模块划分、吞吐目标、容量目标、参考环境、数据保留与版本顺序是本项目的设计决策。所有性能结论必须以项目自己的测量和完整证据包成立。
