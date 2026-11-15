# V1 本地运行与恢复手册

## 服务与配置

V1任务性能升级使用Flyway V14/V15。先停所有旧版平台写入和worker，在同一停止点制作并验证备份，再启动新版；禁止与旧版混跑。V14把现有 `outbox_tasks` 的全部eventId回填到永久唯一表 `outbox_events`；完成超过24小时的DONE任务以后按最多500条一个事务迁入 `outbox_task_history`。审计、控制台分页和历史核验读取 `outbox_task_records`（热/冷合并视图），当前租约领取只查热表；DEAD及未完成任务留在热表，人工重放历史DONE任务会在同一个事务内移回热表。V15给配额清理增加按时间索引。过期nonce/配额每轮每表最多4批、每批500条，清理和归档均每250毫秒安排下一轮（数据库调用本身耗时另计），在目标流量下还需监控实际积压。迁移时若DDL中断，停止写入并从迁移前备份恢复；不可仅删除Flyway失败记录后继续。回滚到旧版前也必须停止全部新写入，按ID分批把历史表完整搬回热表、核对总记录和eventId，再运行旧版；旧版不能直接读取已归档的事件。备份必须包括历史表、永久登记和视图。

若回退到旧版后又产生了任务，再次升级前必须停止所有旧版写入者。在新版本代码目录编译 `scripts/mvn.ps1 -MavenArguments @('-o','-pl','platform','compile')`，将 `DATABASE_URL`、`DATABASE_USER`、`DATABASE_PASSWORD` 设置为待升级数据库的连接信息，运行 `scripts/release/reconcile-outbox-events.ps1 -WritersStopped`。该程序按500条分页补齐旧版写入的永久事件登记，先检查ID/eventId冲突，结束时核对热/历史任务与登记表的一一对应；失败时保持写入者关闭并调查数据，不得直接启动新版。核验成功后方可启动新版。此步骤可重复执行，但必须在无写入者状态下执行。

签名入口总在途数保持 `API_MAX_INFLIGHT`；当上限至少8时，支付/退款/查询分别有1/8、1/8、1/4保留名额，其余共享。支付流量独占时可用名额比原版少，属于公平性取舍；不能据此声称数据库总容量上升。任务空领取最多退避100ms；nonce和配额每表每轮最多4批×500行，每分钟执行一次；通知HTTP每客户端/route上限16连接。

常驻原生环境为Windows Java/Node加Docker MySQL、Keycloak。另已实装独立merchantpay-container-v1应用编排，使用15173/18080/19091/13307/18180端口及独立卷；构建、OIDC、标准负载、扫描和恢复命令见 [本地交付操作](local-delivery.md)。二者均为本机演示，不能据此认定生产编排或R1验收通过。

`scripts/init-local.ps1` 仅在缺失时生成 `.env`，再渲染 `.data/keycloak/realm.json`、`.data/prometheus/prometheus.yml`。本地演示工作台管理员默认使用 `admin/admin`；其他密码、`MASTER_KEY`、API secret 和模拟器 token 都不应入库。首次创建 MySQL 卷之后，修改 `.env` 中 DB 密码不会自动修改库内用户。Keycloak realm 导入也不会自动覆盖既有 realm 的用户、密码或客户端。

主要配置：

| 配置 | 默认 / 用途 |
| --- | --- |
| `DATABASE_URL, DATABASE_USER, DATABASE_PASSWORD` | 127.0.0.1:3307 / merchantpay；UTC 会话，InnoDB |
| `DATABASE_POOL_SIZE` | 32；增加池大小前测量锁等待、磁盘和事务成本 |
| `API_MAX_INFLIGHT` | 16；签名 API 在途上限，过载立即 503 / Retry-After；默认给后台过账留出连接容量 |
| `PAYMENT_MAX_INFLIGHT_PER_SHARD` | 16；16个逻辑桶各自的未完成支付上限，1–4096，所有实例使用相同配置；桶满时新支付503，未知结果与关闭处理中不释放 |
| `MASTER_KEY` | Base64 32 字节，AES-GCM 包装商户密钥；丢失则无法解密 |
| `OIDC_BASE_URL, OIDC_CLIENT_SECRET` | 严格校验 issuer；浏览器、回调与 provider 需使用一致公共地址 |
| `CHANNEL_BASE_URL, CHANNEL_TOKEN, CALLBACK_SECRET` | 独立模拟器与回调密钥，不能当成商户凭据 |
| `DATA_DIRECTORY` | `.data/platform`，摘要寻址原始账单和流式导出 |
| `WORKERS_ENABLED` | true；false 只用于维护 / 故障恢复演练；仍可持久化受理，未完成容量耗尽后新支付503 |
| `METRICS_PASSWORD` | metrics 只读观测专用凭据 |
| `BIND_ADDRESS, PLATFORM_PORT, CONSOLE_URL` | 默认本机入口 |

正式环境需要 HTTPS、Secure 会话 Cookie、独立秘密管理、固定持久卷、生产 OIDC 服务和备份。local Keycloak 使用开发模式 H2，模拟器使用 H2 文件，均不是高可用部署。V1 未部署 Redis / Kafka / ClickHouse，也不对外提供真实资金能力。

V1 会话授权使用登录时的 OIDC 声明；Keycloak 后台变更角色不会主动撤销已有平台会话，更改后需退出重新登录。当前 30 分钟是空闲会话超时，不是强制重新登录周期；集中会话撤销和更细角色管理属于后续版本。导出下载每次检查平台当前会话身份与范围，不使用长期公开下载 URL。

## 正确性与恢复

worker 使用数据库租约和 token fencing，30 个快速名额、2 个账单 / 导出名额。任务执行可重复，过账由根行锁和来源事件唯一键保护。运行中进程退出后，其他 / 重启进程在租约过期后重新领取。未知支付查单，未知退款保留预占；不要手工将 UNKNOWN 改成 FAILED 或删除任务。

任务确认有10ms聚合等待目标，按PK顺序执行逐条lease token条件更新，在同一事务提交；没有省略逐任务fencing。提交返回前worker名额与心跳仍保留；一条失败会回滚整组并各自重试，业务已提交而确认未知时仍可能重复执行。商户通知按eventId幂等。渠道网络与原始回执不进入结果事务，财务结果和渠道尝试最终状态同事务；同步冲突证据在结果事务回滚后保存。详见[后台提交优化](../quality/2026-10-19-task-commit-batching.md)。

V12增加持久化支付容量计数，升级前停止全部旧版写入与处理实例，按备份流程准备恢复点后再迁移、启动新版；禁止新旧混合滚动，不能直接回滚运行不维护计数的旧JAR。迁移保留历史超限与未知单，终态、账务和容量释放同事务提交，重启不重置计数。已有业务号重试、查询、关闭及退款可继续；不能手工减计数或删除未知单解除背压。核对计数与非终态支付须用同一一致性快照。详见[支付积压背压](../quality/2026-10-19-payment-backpressure.md)。

自动退避最多 12 次，耗尽进入 DEAD；工作台任务页授权人工重放会留下审计。渠道长期 PENDING 也会耗尽自动重试预算，应查明渠道事实后由运营人员重放 / 关闭，不能视为未扣款。商户通知失败不回滚已完成账务。

确定性的 `CHANNEL_EVIDENCE_CONFLICT` 直接将当前执行任务隔离为 DEAD，不继续自动退避。包括同渠道不同支付 / 退款复用流水号：业务事务回滚、原文和冲突记录保留、同步尝试标为 CONFLICT；退款预占继续保留。Inbox 对应记录标为 CONFLICT，处理任务结束。应先核查渠道事实，再按授权流程重放，不能删除唯一约束或手工释放预占。其他数据库 / 网络故障仍按正常退避重试。

已请求关闭的支付即使查单超时也保留 `CLOSE_PENDING`。旧数据中已存在关闭任务但状态为 `UNKNOWN` 的订单，再次关闭复用原请求，不重复创建事件；DEAD 任务仍按授权重放流程处理。成功、失败及关闭终态均验证渠道与金额、币种；错配失败保留退款预占并记录冲突。模拟渠道未提交即关闭的零金额 tombstone 必须匹配本地关闭意图。

同步回执在身份校验之前留存原文；关联错配按请求的预期业务号写入冲突记录，渠道尝试标为 `CONFLICT`。不要将返回的错误聚合号直接作为冲突归属。

`scripts/restart-recovery.ps1` 会停止**本项目记录**的三个进程，以 worker 关闭模式启动并受理一笔，再重启恢复，SQL 核验一笔成功、一张凭证、三条平衡分录、一个成功事件与一个渠道尝试。不停止 Docker、不删除记录。运行时避免并行开展浏览器或负载测试。

`lost:` 业务号：渠道成功持久化后首次响应 504；`fail:`：明确失败；支付 `pending:`：保持处理中，可请求关闭。它们仅为模拟器故障开关，正常商户业务号无需这些前缀。

## 账单与导出

工作台列表可按北京时间选择不超过 31 天的开始 / 截止日期，截止日期不含；重置按钮恢复最近七天，筛选变化重置分页。跨期支付、退款与凭证可使用异步导出。平台的商户选择器远程搜索，完整编号优先匹配；精确商户查询与搜索同样实施服务端租户校验。

支付详情中的退款、凭证、渠道尝试、通知 / 任务及矛盾证据分别分页，每类每页最多 50 条；分录随本页完整凭证展示，不拆开借贷分录。时间线汇总各标签页的当前页，较早记录在相应标签页翻阅。刷新回到最新记录，切换支付或商户重置分页。升级时 Flyway V11 增加凭证的支付 / 时间 / ID 索引，保留现有数据。

对账原始文件 `.data/platform/bills/<sha256>.csv` 不可变。同商户 / 渠道 / 日 / 版本相同内容返回原批次，内容不同需递增版本。SHA、结构、UTF-8 编码、笔数或金额不符进入 QUARANTINED，不发布部分结果；基础设施异常进入 FAILED，授权续跑从已提交检查点恢复。

上传及渠道拉取先流式保存原文件，再在同一数据库事务中登记批次、任务与操作人审计；审计失败会回滚登记，原文件仍保留，不要据此推断批次已受理。账单只接受 LF / CRLF 行结束符，字段内或文件末尾的孤立 CR 会隔离；单行内容上限 1,024 字符。控制台每次打开上传需重新选择文件，读取摘要和提交期间暂时禁止关闭弹窗或替换文件。

每 500 行提交一次，单份 128 MiB / 100 万行，单行最多 1024 字符。本地状态快照只冻结一次，不随重试漂移。原始单边、金额 / 手续费 / 状态 / 引用和重复行有差错类型。账单不会自动改账或发起真实结算。

导出最多 365 天，MySQL REPEATABLE_READ 一致性快照，流式读取，ZIP 内每个 CSV 最多 100 万条，附 manifest；CSV 转义公式前缀。只有 COMPLETE 可下载，下载重新校验发起人当前权限、商户范围与 24 小时有效期。V8 之前无发起人证明的导出必须重新生成。存储空间不足会失败，修复后重放任务；不要把 `.part` 临时文件当成完整结果。

平台用户下载时，EXPORT_DOWNLOAD 审计使用已授权导出的实际商户，按该商户筛选及全局审计均可查询；其他商户筛选不会显示。第四轮修复没有改写历史 merchant_id 为空的下载审计，这些旧记录仍保留在全局审计中，可通过其资源 ID 关联原导出。

批任务除了 Outbox 租约 token，还校验聚合 run_token，避免同批次独立人工重试与旧执行互相覆盖。每次导出执行写独立 `<id>-<token>.zip`，仅当前执行可发布数据库指针；失租文件不用于下载，可能保留未引用文件供排查。清理前先核对数据库引用及备份，不能按文件新旧直接删除正在使用的证据。

## 观测与诊断

503来源使用固定`X-Retry-Reason`头及`merchantpay_api_unavailable_total{reason=...}`核对；阶段计时包含凭据查询、nonce提交、等待配额及下游处理，标签不含业务号或凭据。`scripts/load/baseline-local.ps1 -CalibrateObserver`沿用资源/进程/源码守卫，预热600秒后固定full/after/after/full四轮。after仅结束后核验账务，不产生完成TPS或延迟证据；校准数据有效与在线基线资格分开判定，详见[口径与结果](../quality/2026-10-19-api-unavailability-observer.md)。

`CommitWaitSampler.java`增加100ms休眠加查询耗时的当前事件采样，只读且不自动开启监控；七项consumer缺失/关闭时失败，显式COMMIT历史分类限定同事务。临时启用stage/wait监控须先保存原值，并由外层异常/超时路径独立恢复；输出不保存SQL摘要。当前等待年龄不是完成时延，150ms关联不是逐笔因果。I/O指纹另增加binlog分组延迟/数量及log-writer-threads，防止配置差异被忽略。本次5000µs/32候选已拒绝，默认0/0和原监控已恢复，详见[方法、恢复及结果](../quality/2026-10-19-binlog-commit-waits.md)。

固定D0资源测量：排空后停止项目，`scripts/start-local.ps1 -Performance -SkipBuild`启用可选覆盖（MySQL4CPU/4GiB/cache1GiB；原生CPU/堆限制）。禁止在业务进程运行时切换模式。先执行 `scripts/load/baseline-local.ps1 -CheckOnly`核验，再执行无CheckOnly的完整600秒预热与三轮协议。普通模式需停止后用原启动命令恢复；数据卷和回执保留。配置、退出与失败证据规则见[D0基线说明](../quality/2026-10-19-d0-performance-baseline.md)。

查看 `.work/platform.stdout.log`、`.work/platform.stderr.log` 和模拟器日志，以 `X-Request-Id` 关联 API 审计。不要将密钥、Authorization、Cookie、签名原文完整输出到日志。`scripts/db-inspect.ps1` 是只读的本地诊断，显示任务、最近交易、借贷差额和 EXPLAIN。

Prometheus 模板和规则在 `infra/prometheus`，指标包括任务状态、队龄、业务状态、连接池和 HTTP。`up=1` 只证明抓取成功，不代表资金正确或延迟达标。OTLP 导出默认关闭；完整追踪后端和 Grafana 尚未部署。

`merchantpay.tasks.execution` 按kind/outcome记录领取后的业务执行及完成/重试写入时间，不含排队；outcome含completed、retried、lease_lost、failed。只读负载诊断额外采样各桶已提交容量，样本不是实时最大值，准入直接依赖事务条件更新。

`merchantpay.tasks.completion.batch.size` 记录已返回提交成功的确认组大小（含返回false的租约校验项）；`merchantpay.tasks.completion.batches` 区分committed/failed组。组大小不固定，低并发可为1；这些是客户端提交组指标，不能拿显式COMMIT SQL摘要次数替代全部隐式/显式提交或fsync次数。

在另一个终端先运行 `& scripts/load/diagnose-local.ps1 -Seconds 45`，再执行有界负载。诊断使用已有本地root及metrics凭据，只读，不修改监控开关或数据库配置。输出名带时间及随机后缀；`.jsonl`为约每秒样本，`.digests-before/after.json`为规范化SQL摘要，`.io-before/after.json`为I/O端点快照，`.io-delta.json`为精确整数差值。读取全部监控消费者及相关文件/提交阶段/日志锁开关；fileDeltas和stageDeltas分别给出文件I/O及提交阶段差值。未开启阶段返回null，未计时阶段保留次数但时间为null；日志锁只记录开关，不能从空白推断等待为0。阶段计数并非事务或fsync次数，阶段/文件/SQL累计时间会重叠。

模拟器启动时以CREATE INDEX IF NOT EXISTS建立待回调/待支付索引，避免已确认历史回执使健康统计与每秒回调轮询持续扩大扫描。旧文件首次升级需要构建索引；先停本项目模拟器、备份H2文件，再启动，保留全部回执。回调数量必须实际排空，不能仅因财务已过账就当作渠道完全空闲。实现与本机测量见[渠道扫描与提交阶段报告](../quality/2026-10-19-channel-health-commit-stage.md)。

分析前检查io-delta的valid及problems，确认快照覆盖负载和观察结束、采样无错误、持久性配置一致。配置/监控端点变化、Uptime或计数回退、文件事件集合变化会使区间无效。缺失或未计时的值为null，不补0。端点检查不能排除区间内短暂改动、监控丢样或所有重置后重新增长的情况，受控实验仍须单独保证期间配置稳定。

文件计时单位为累计皮秒，除以1e12为累计秒数；先作整数差再换算。SUM_TIMER_MISC包括同步、打开、关闭等多种操作，并非专属fsync时延。Innodb_os_log_fsyncs与Innodb_data_fsyncs分别保留，不能直接相加；显式COMMIT摘要不含隐式提交。并发语句和文件等待可能重叠，不能相加为端到端延迟，也不能仅凭文件读计时判断宿主磁盘饱和。缓存物理读与逻辑读应按同一区间比较，低读量也不保证提交等待下降。

I/O状态快照另保留`Binlog_cache_use`、`Binlog_cache_disk_use`、`Binlog_stmt_cache_use`及精确差值：依次为使用事务binlog缓存的事务数、缓存溢出临时文件的事务数、非事务语句缓存使用数。缓存使用可能含回滚，不能当作成功业务提交或fsync次数；缺失仍为null、回退使区间无效。nonce分组候选已完整撤回，当前签名链路仍是原版逐请求nonce持久化，见[事务盘点及候选回退](../quality/2026-10-19-nonce-commit-batching.md)。

300/s 测试曾暴露任务 OR 扫描、空 BULK 分类扫描和 JDBC 连接池耗尽。已分离状态 / 分类索引并批量领取，保留了全部受理数据；后续还要压测数据库热点、入站背压与分布式配额。不能通过清空积压或只统计快请求宣布达标。

现已增加签名 API 在途背压，连接获取耗尽也明确为可重试 503。此处容量限制与商户固定窗口配额不同，不能据此推断 300 TPS 已通过。所有 5xx、超时或连接中断都可能发生在数据库提交以后，客户端必须使用原业务号查询或重试。

## 备份与恢复

备份集合：MySQL 一致性备份、`.data/platform` 原始证据 / 导出、模拟器 H2 文件、Keycloak 用户配置和 `.env` 对应的秘密版本（尤其 MASTER_KEY）。密钥备份独立加密保存。恢复应在隔离环境验证后切换，不直接覆盖正在服务的数据库。

MySQL 使用 `mysqldump --single-transaction --no-tablespaces`，通过受控环境或 option-file 提供凭据，避免命令行明文密码。单事务数据库快照不能单独保证正在写入的文件集合一致；在本地全量恢复点，先暂停本项目写入并停止应用，备份完成后再恢复。H2 文件只能停模拟器后复制，不能复制正在写入的文件冒充一致性备份。

已通过 Testcontainers 的真实 MySQL `mysqldump → 清除独立测试库表 → mysql 恢复 → 重复成功回调` 测试：恢复后退款累计不丢失且凭证 / 分录不重复。该证据是小数据恢复验证；没有声称完整 D1 灾备、跨地域 RPO / RTO 或密钥轮换灾备达标。

新增scripts/release/backup-local.ps1和restore-local.ps1面向独立容器项目，停写一致点包含MySQL、平台文件、模拟器/Keycloak H2、准确密钥版本及可选本地S3卷；ID不可覆盖，归档用Windows当前用户DPAPI加密。恢复使用独立端口/副本，结束后停止恢复容器；解密后的.data/restores为敏感数据。1GiB压缩归档上限、本机用户绑定、local对象限定和实际覆盖见 [完整操作与限制](local-delivery.md#成套备份及隔离恢复)。原生历史库和完整D1尚未通过此套恢复认证。
