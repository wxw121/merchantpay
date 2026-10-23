# V1 本地交付操作

适用范围：Windows PowerShell 7、Docker Desktop Linux 引擎、项目自带 Java 25 / Node 24。当前版本仍为 `1.0.0-SNAPSHOT`；本页提供实际可运行入口，最新状态见 [非发布剩余项验收](../quality/2026-10-20-v1-nonrelease.md)，旧轮次证据保留。

## 独立容器部署

```powershell
& scripts/release/build-images.ps1
& scripts/release/start-containers.ps1
& scripts/release/scan-images.ps1
# 显式采用已做重复D0峰值对照的资源配置；保留原金融数据：
& scripts/release/start-containers.ps1 -Storage s3 -Performance
```

控制台 `http://127.0.0.1:15173`，平台 `18080`，模拟器 `19091`，MySQL `13307`，身份服务 `18180`。项目名固定为 `merchantpay-container-v1`，使用独立卷，不复用原生服务的金融数据。管理员仍为 `admin/admin`，仅本机演示使用。平台和浏览器共用身份地址 `http://merchantpay-keycloak.localhost:18180`；该地址在容器网络中有别名，Chromium 的 `.localhost` 地址解析到本机。

`-Performance`叠加`infra/compose.app.performance.yaml`：MySQL2CPU/2GiB、buffer pool1GiB、双1；应用2CPU/1GiB、32连接池、API在途上限128、FAST工作并发96。普通配置API上限仍16、FAST仍30；两种模式的渠道HTTP池默认每地址64连接，可用`CHANNEL_HTTP_CONNECTIONS_PER_ROUTE`配置1..256，后台可用`FAST_WORKER_CONCURRENCY`配置1..256，单次数据库领取始终≤64。原生`start-local.ps1 -Performance`也显式采用FAST96。

新任务分类为渠道FAST、回调INBOX、商户通知NOTIFY；96名额按64/16/16保留，普通30名额为20/5/5，BULK另限2。配置1/2时仍共享总额度并轮转。升级V13前先停止负载、等待全部业务及任务排空、停止所有旧API写入者与worker，再启动新版本；回退同样先排空并停新版本。不支持混合滚动。后续性能及连接池隔离状态见[支付路径优化记录](../quality/2026-10-22-flow-optimization.md)。

后续启动须继续显式传该开关；Storage选择会保存，Performance开关不保存。切换预算会重建受影响服务，应先停止负载并排空任务。旧构建的两次120秒尖峰、30分钟混合和完整四小时复验通过，不代表新构建已继承该结果。2026-10-23交付的新构建目标复测仍未达标，具体吞吐、延迟、拒绝和失败调优见[支付路径优化报告](../quality/2026-10-22-flow-optimization.md)。连接池隔离默认关闭，启用时API12/后台20之和仍为32，不是增加资源。

容器启动入口会先独占负载/备份共用的`.work/performance-baseline.lock`，持锁期间明确拒绝启动或切换配置，避免误重建正在验收的服务。锁拒绝反例纳入本地verify与CI定义，测试不会调用Docker或停止真实服务。

扫描保存实际镜像 ID、报告 SHA 和 HIGH/CRITICAL 计数。`scan-images.ps1` 需要 Trivy 及可用漏洞数据库；数据库下载失败不能得到通过。扫描结果不覆盖 Windows，也不证明二进制 JDK 没有漏洞。

两套实际部署的应用与Keycloak均已核验Oracle JDK25.0.5，MySQL8.4.13（官方签名RPM）及双1保持；后端301项包含真实MySQL/S3回归通过。身份服务切换前冷备、公钥完整材料和两角色登录通过，切换后签名支付/退款/查询、渠道事实及HTTP通知收敛。三应用、补丁MySQL/S3/Keycloak的HIGH/CRITICAL为0；Prometheus已从10 HIGH的3.13.4更新到固定digest的官方3.13.5，旧TSDB冷备、现有配置/四条规则及真实鉴权抓取通过，但两个二进制仍各有gRPC高危，**全配置安全扫描仍失败**。具体固定ID、版本和失败报告见最新验收。

## 对象存储

```powershell
& scripts/storage/start-local.ps1
```

本地 S3 兼容测试服务为 RustFS `1.0.0-beta.12`，端口 `19002`，桶 `merchantpay-v1`，独立卷和私有凭据位于 `.data/storage/.env`。这是联调环境，不是生产选型认证。脚本仅创建缺失桶，保留已有对象。

平台启动前设置：

| 环境变量 | 值 |
| --- | --- |
| `OBJECT_STORAGE_TYPE` | `s3` |
| `S3_ENDPOINT` | 原生服务 `http://127.0.0.1:19002`；多容器需配置可达地址 |
| `S3_BUCKET` | `merchantpay-v1` |
| `AWS_REGION` | `us-east-1` |
| `S3_PATH_STYLE` | `true` |
| `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` | 从私有配置导入，不能写入浏览器或公共报告 |

AWS 部署可留空 endpoint，并使用 SDK 默认凭据链。账单和导出使用不可变 attempt 对象，数据库指针决定可见版本；S3 指针明确带 `s3:`，已有本地指针仍读本地文件，因此迁移后仍要保留旧文件。切回 local 不能读取已有 S3 指针。S3 缺失/不可用会报错，不返回空文件。

当前原生平台使用 **local**，独立容器平台已使用 **s3** 并保留旧local对象。`scripts/release/start-containers.ps1 -Storage s3` 配置可达的存储网络并保存选择，后续省略Storage会沿用；切换须排空进行。非空local/S3账单及ZIP已整套恢复验证，生产S3仍需独立认证。

## 生成式接入客户端

```powershell
& scripts/pnpm.ps1 -PnpmArguments @('--dir','../sdk','install','--frozen-lockfile')
& scripts/pnpm.ps1 -PnpmArguments @('--dir','../sdk','generate')
& scripts/pnpm.ps1 -PnpmArguments @('--dir','../sdk','verify')
& scripts/pnpm.ps1 -PnpmArguments @('--dir','../sdk','smoke')
```

以 `docs/api/openapi.json` 为源，生成模型和五个 operationId 方法；`check:generated` 检查契约和生成物漂移，发现漂移不会自动掩盖。运行时使用 Node 标准库签名，金额保持字符串。签名覆盖真正发送的 UTF-8 JSON、路径和查询串，每次显式调用使用新 nonce。超时/5xx 保留不确定结果上下文，写操作不自动重发；按原业务号查证。详见 [SDK 使用说明](../../sdk/README.md)。

## 标准负载与数据夹具

容器模拟器默认 demo；负载验收须显式开启固定种子的标准配置：

```powershell
$env:APP_SIMULATOR_PROFILE='standard-v1'
& scripts/release/start-containers.ps1
# 独立容器首次启动后，其 API 凭据在平台数据卷内，单独复制到私有目录：
docker cp merchantpay-container-v1-platform-1:/data/platform/local-api-credentials.json .data/container/api-credentials.json
$env:DATABASE_URL='jdbc:mysql://127.0.0.1:13307/merchantpay?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true'
$env:LOAD_NOTIFICATION_URL='http://simulator:9091/merchant-webhook'
& scripts/load/run-v1.ps1 -Protocol Dataset -DatasetId demo-seed -Payments 500 -PayRate 10 -CredentialsFile .data/container/api-credentials.json -BaseUrl http://127.0.0.1:18080 -SimulatorUrl http://127.0.0.1:19091 -DatabaseUrl 'jdbc:mysql://127.0.0.1:13307/merchantpay?connectionTimeZone=UTC'
& scripts/load/run-v1.ps1 -Protocol Smoke -CredentialsFile .data/container/api-credentials.json -BaseUrl http://127.0.0.1:18080 -SimulatorUrl http://127.0.0.1:19091 -DatabaseUrl 'jdbc:mysql://127.0.0.1:13307/merchantpay?connectionTimeZone=UTC'
```

标准种子 `20261019`，按业务号确定约 98% 成功/2% 业务失败、延迟分布与成功回调约 20% 重复。小样本比例会偏离总体目标。支付、退款走真实签名 HTTP，通知走真实 HTTP，渠道有独立持久化事实。查询 80% 随机覆盖凭据所有线上交易，20% 热点，凭据始终绑定交易所属商户。退款每轮选择不同支付，避免把同一支付的并发退费分摊顺序差异当成错误。

`Dataset` 使用稳定业务号、每 1000 笔检查点和固定目录，可重复运行续跑；这一路生成的是当前日期 API 数据，不等于 D1 历史分布。`Truth -RunId <原ID>` 只读重查渠道事实，另存报告，不修改原压测报告。失败支付的 `fee_minor` 是平台报价，渠道实收为 0；成功支付才核对实收费用。所有 5xx、未发送、未知及观察耗时都保留。

```powershell
# 完全独立的历史夹具，禁止连接正常业务库
& scripts/load/d1-fixture.ps1 -Id sample -Payments 100 -Merchants 10 -Days 3 -VerifyRecovery
# 全量需磁盘前置门槛；不足则在装载前拒绝
& scripts/load/d1-fixture.ps1 -Id full-d1 -Full
```

离线历史夹具仅连接 `127.0.0.1:13308/merchantpay_fixture_<8位摘要>`，独立 MySQL 卷、H2 渠道及密钥，不修改正常平台时钟。使用真实领域服务，每 80% 支付做五笔合法部分退款，以实际成功事件形成目标规模，不直接拼分录。真实工作时钟负责租约，恢复包括末尾已完成游标的未确认任务；历史日期截至昨天。`-VerifyRecovery` 只对该隔离夹具模拟失效租约及临时费用篡改，异常路径恢复原值。

全量目标 100 万支付、至少 500 万分录、1000 万实际 Outbox 事件、1000 商户、30 天；全量前检查工作盘至少 30% 空间，Docker 数据盘空间与独立运行预算仍需另外确认。本机全量前置检查未通过。离线夹具不覆盖签名/HTTP/通知吞吐，也不替代在线混合故障和对账差错分布验收。

`run-v1.ps1` 提供 C1/C2/C3/Soak；`-Scaled` 明确标为开发测量。正式入口先要求完整 D1，热身 600 秒、C1/C2 测量 900 秒、C3 1800 秒、普通协议三轮，Soak 四小时；混合场景还需已认证控制台 cookie/CSRF、十万行账单/导出覆盖。空样本、数据或观察缺失不会通过，所有报告的 R1 认证仍为 false。

正式C2/C3查询到达率为2050/513，给正常尾部RTT留余量；成功查询目标仍为2000/500，不能用结束窗外返回或受理数量替代。凭据需覆盖完整D1商户，单key500/s配额保持，两个演示商户不适合签署正式2000QPS。

## 成套备份及隔离恢复

```powershell
& scripts/release/backup-local.ps1 -Id my-checkpoint
# 原生历史开发集；要求三个原生进程身份可核验、任务排空且没有S3业务引用：
& scripts/release/backup-local.ps1 -Source native -Id native-checkpoint
& scripts/release/restore-local.ps1 -Id my-checkpoint
# 首次恢复中断后，保留隔离副本，只继续检查，不重新覆盖导入
& scripts/release/restore-local.ps1 -Id my-checkpoint -Resume
# 原生来源必须显式匹配，才能使用对应的压缩归档预算：
& scripts/release/restore-local.ps1 -Source native -Id native-checkpoint
& scripts/release/test-backup.ps1 -ExistingId my-checkpoint
# 已成功恢复的native/local归档：启动恢复副本，实测签名支付/退款与租户隔离
& scripts/release/verify-restored-api.ps1 -Id native-checkpoint
```

默认备份面向独立容器项目，要求任务/渠道排空，短暂停止该项目应用、模拟器、Keycloak及可选本地S3。`-Source native`改为核验并停止本项目记录的三个原生进程及原生Keycloak，打包原生目录；停服前后均确认没有S3业务引用，避免漏备共享对象。MySQL使用single-transaction逻辑备份，平台证据、H2、身份及精确密钥版本一并打包。`finally`恢复原运行服务和原生运行模式，并等待身份及HTTP健康；失败使命令报错，备份文件生成与源服务恢复分开记录在`backup-<Id>.json`和`backup-<Id>-source.json`。备份ID不可复用，重复ID拒绝且原文件摘要不变。文件清单逐项保存SHA-256。

`.data/backups/<Id>.dpapi`使用Windows CurrentUser DPAPI加密。默认container来源最多256MiB**压缩归档**，恢复加密输入上限257MiB；显式native来源最多1GiB压缩归档，恢复输入上限1025MiB，且必须与归档来源匹配。这些是加密缓冲区限制，不是原始文件总和或总进程内存上限。原生历史集实测明文2033487314字节、压缩557470054字节。依赖创建备份的Windows用户/主机，跨主机密钥托管、D1备份容量和RPO/RTO未认证。`.data/restores/<Id>` **是解密后的敏感恢复副本**，包含运行密钥和业务数据，只用于本机隔离验证，不能作为去密钥证据分享。

恢复使用独立 `13309/18181/19003` 端口，校验归档、逐条渠道事实、商户密钥解密、凭证平衡/退款累计/任务、引用本地账单 SHA/导出 ZIP、身份签名公钥，以及 S3 探针对象实际字节。恢复先启用归档S3，再严格按数据库中的local/S3指针读取原对象；已有S3业务引用的账单SHA及ZIP已实际验证。`scripts/release/test-restored-objects.ps1 -Id <已恢复ID>` 在已有非空S3恢复集上验证禁用S3必须失败，不修改资金行。测试结束停止恢复容器，保留副本供检查，不替换源部署。

`verify-restored-api.ps1`仅接受已经验证的native/local恢复集，启动恢复库与身份容器，再以本项目Java启动恢复应用及模拟器（18082/19092）。使用恢复密钥真实签名支付10000分、退款3000分、验证另一商户404，等到两张凭证/六条分录/两次实际HTTP2xx及全任务收敛，再停止恢复进程并全量核对资金、渠道和对象。只向恢复副本写入；原归档保留，API结果另存`restore-<Id>-api*.json`，已有最终证据拒绝覆盖。这是本机业务续跑验证，不是跨主机RPO/RTO或恢复工作台浏览器认证。

## 验证入口和证据

`scripts/verify.ps1` 包含后端、格式、负载守卫、SDK、前端及构建；`-Browser -Scale` 增加原生浏览器与十万行对账；`-Release` 还启动真实本地 S3，扫描应用镜像及两份Compose实际配置的全部四个基础镜像。当前Prometheus高危会使该门槛失败。Release 选项不代表发布门槛已签署，镜像构建/运行、前端性能、成套恢复和正式负载各有上述独立入口。

`.github/workflows/verify.yml` 提供相同核心本地检查的 Linux CI 定义，代码远端为 [wxw121/merchantpay](https://github.com/wxw121/merchantpay)。远程 CI 是否通过以对应提交的 Actions 记录为准，不继承本地检查结论。私有原始产物在 `.work`，公开报告仅使用计数、摘要、版本和覆盖范围。

## 受限客户端与本机长时序

```powershell
& scripts/browser/verify-client.ps1 -Mode Lcp
& scripts/browser/verify-client.ps1 -Mode Feedback
# 通过真实OIDC准备负载会话；不创建支付、账单或导出，重启平台后重新准备：
$env:RUN_LOAD_SESSION='1'
& scripts/pnpm.ps1 -PnpmArguments @('exec','playwright','test','load-session.spec.ts')
# 默认30分钟混合、4小时Soak；开始前检查空间及所有服务：
& scripts/load/run-local-endurance.ps1
```

浏览器树由Windows Job限制4逻辑CPU/8GiB提交内存；运行器和子进程也纳入，CDP只负责网络条件。反馈测量使用受控API响应，不代表真实业务完成延迟。长稳是2CPU/2GiB负载进程树的D0缩比，含基线、每小时五分钟尖峰、HTTP通知、非空S3批次/导出、15个列表/统计worker、逐分钟资源/队列/资金及最终通知收敛。正式R1/D1和十万行混合目标保持独立门槛。

完整D1检查实际Docker数据盘，并为装载、未压缩归档、隔离恢复副本和新增负载预算后保留30%空间；当前本机不满足。不要通过删除已有业务卷或放宽持久化参数绕过。

脚本临时环境由`ProcessEnvironment.ps1`统一还原，不存在的变量会删除，原有空值/非空值保留。可运行`scripts/test-process-environment.ps1`检查，避免同进程启动S3后遗留空配置影响后续验证。
