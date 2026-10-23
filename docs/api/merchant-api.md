# 商户 API 契约 V1

实现入口：`OpenApiController`、`SignatureFilter`、`PaymentService`、`RefundService`。基础路径 `/api/v1`，版本头 `X-Api-Version: 1.0`（省略默认 1.0）。正式外部入口使用 HTTPS，回环 HTTP 仅供本地模拟。

机器可读的五个商户端点契约：[OpenAPI 3.1](openapi.json)。签名由调用方按下文计算，前端当前仍使用手写类型客户端，没有声称已自动生成 SDK。

## 认证与签名

带 `X-Key-Id`、`X-Timestamp`（十位 Unix 秒）、`X-Nonce`（16–100 位字母数字、下划线或短横线）、`X-Signature`（HMAC-SHA256 小写十六进制）。原文五段用单个换行连接，末尾无换行：

```text
timestamp
nonce
HTTP_METHOD
raw_path_and_query
sha256_hex(raw_utf8_body)
```

GET 的空 body 仍计算摘要。签署实际发送的 UTF-8 字节、原始路径和查询串，签名后不能重新序列化 JSON。有效时间服务端 ±300 秒，nonce 持久化去重 601 秒。重试使用**原业务号、新 nonce、新签名**。重复 nonce 返回 409；相同业务号和关键参数返回原对象，关键参数不同返回 409。可运行示例：`scripts/api-example.ps1`。

每个 key 固定窗口每秒 500 次，由 MySQL 计数；配额不代表吞吐承诺。密钥轮换保留旧 key 最长一小时，吊销立即阻止新认证。商户范围来自 key，不能在参数中切换身份。签名 JSON 最大 64 KiB。返回 `X-Request-Id` 用于日志定位。

同一 key / 服务端秒的并发配额检查采用10ms聚合等待目标，在同一MySQL事务中累计和读取，提交后逐请求分配500次边界；跨实例和进程重启共享原窗口。实际等待包含调度与数据库耗时，10ms不是端到端延迟上限。nonce仍逐请求先持久化，429拒绝的nonce也不能重放。配额事务或提交结果无法确认时返回503，可能已经消耗配额；不能通过重试新业务号绕过。调用方等待最多5秒，未结束的数据库工作仍占有限工作容量，不随调用中断或超时释放。

签名 API 默认最多 16 个在途请求，为后台过账保留连接容量；超过时返回 503、`Retry-After: 1`，使用原业务号退避重试或查单。可用 `API_MAX_INFLIGHT` 调整（1–128），应结合数据库池和独立负载验证，不能通过无上限等待实现所谓高并发。

新支付还受未完成业务量限制：16个逻辑桶默认每桶16单（`PAYMENT_MAX_INFLIGHT_PER_SHARD`，1–4096，所有实例保持相同值），桶满返回503、`RETRY_WITH_SAME_BUSINESS_KEY`、`Retry-After: 1`。其他桶有空位也可能因当前桶满而拒绝。创建被拒会回滚支付与Outbox，但签名nonce已消耗；使用相同业务号和参数、新nonce退避重试。已有业务号返回原单，不再占新容量，参数不符仍409。`PROCESSING`、`UNKNOWN`、`CLOSE_PENDING` 持续占位，成功/失败/关闭终态提交才释放。支付查询、关闭、退款不受这个新单门槛影响，仍受各自认证、限流及数据库可用性约束；HTTP错误有提交不确定性时仍须按原业务号查证。

## 支付与退款

| 方法 / 路径 | 成功响应 | 作用 |
| --- | --- | --- |
| POST `/payments` | 202 PaymentView | 持久化受理，不表示支付完成 |
| GET `/payments/{id}` | 200 PaymentView | 精确查询权威状态 |
| POST `/payments/{id}/close` | 202 PaymentView | 请求关闭，等待渠道证据 |
| POST `/payments/{id}/refunds` | 202 RefundView | 幂等申请并预占退款额度 |
| GET `/refunds/{id}` | 200 RefundView | 查询退款状态 |

金额为规范正整数**字符串**，单笔 1–1000000000 分，仅 CNY；不接受符号、空白、小数、科学计数或前导零，JSON 数字也拒绝。

```json
{"merchantOrderNo":"order-20261018-001","amountMinor":"10000","currency":"CNY","channel":"MOCK_A"}
```

可选 `notificationUrl`：HTTPS、443、公网域名、无账号 / fragment，连接时再过滤实际地址，不跟随重定向；省略可用商户配置。固定本地演示接收端由 local 配置单独允许。

```json
{"merchantRefundNo":"refund-20261018-001","amountMinor":"3000"}
```

PaymentView：`id,merchantId,merchantOrderNo,amountMinor,feeMinor,currency,status,refundedMinor,reservedMinor,refundedFeeMinor,channel,channelReference,notificationUrl,createdAt,updatedAt`。

RefundView：`id,paymentId,merchantId,merchantRefundNo,amountMinor,refundedFeeMinor,status,channel,channelReference,createdAt,updatedAt`。金额为字符串，时间为 ISO 时间。

支付状态 `PROCESSING / UNKNOWN / CLOSE_PENDING / SUCCEEDED / FAILED / CLOSED`，退款状态 `PROCESSING / UNKNOWN / SUCCEEDED / FAILED`。未知结果继续原业务号查单，不能另建业务号重发。未知退款仍占额度；关闭可能发现渠道已成功。跨商户与不存在对象统一 404。

支付、退款和关闭受理时，业务变更、对应渠道任务及操作人审计在同一数据库事务提交；审计写入失败时本次变更回滚。网络中断仍可能发生在提交之后，调用方应继续按原业务号查单和重试。

手续费 0.6%，分单位 HALF_UP。累计退费 `round(原手续费 × 累计成功退款 / 原金额)`，本次返还取与之前累计值的差额。失败只释放一次预占。无真实出款、清算或可提现余额。

## 商户通知

至少一次投递，JSON 包含 `eventId,eventType,contractVersion,data`。收件方按 eventId 去重、本地提交后回复 2xx；超时、进程恢复或人工重放可重复到达。有效终态不被旧通知覆盖。

头为 `X-Key-Id,X-Timestamp,X-Nonce,X-Signature`。通知原文为 `timestamp + "\n" + nonce + "\n" + rawBody`，HMAC-SHA256 使用对应 key 的 secret；与入站 API 五段签名不同。不要对重新编码后的 JSON 验签。渠道回调使用独立服务间 secret，不能暴露给商户。

## 错误与重试

应用错误为 `{"code":"...","message":"..."}`；会话与 CSRF 安全链可能返回空 401 / 403。

| 状态 | 含义 / 动作 |
| --- | --- |
| 400 | 修正参数、金额或版本 |
| 401 / 403 | 检查签名、时间、key、权限 |
| 404 | 不存在或不属于当前商户 |
| 409 | 按 code 区分重放、幂等冲突、额度和状态冲突 |
| 429 | 退避，新 nonce，原业务号 |
| 5xx / 网络中断 / 超时 | 可能已提交，先查单，再以原业务号退避重试 |

应用生成的503附带`Retry-After: 1`和固定分类头`X-Retry-Reason`，用于区分入口在途、配额工作/等待、支付容量与数据库异常；错误码仍为`RETRY_WITH_SAME_BUSINESS_KEY`。分类头用于诊断，不改变按原业务号查证/重试的规则；网关或旧版本可能缺少该头，未知分类应按未分类处理。枚举及计数口径见[503来源与观察器对照](../quality/2026-10-19-api-unavailability-observer.md)。

## 控制台与账单

`/api/console/**` 使用 OIDC 会话。所有写请求先从 `/session` 取 `csrfHeader`、`csrfToken`，带对应头；角色和租户由服务端校验。列表 `from/until/status/cursor` 默认 7 天、最多 31 天，签名游标包含范围和有效期。异步导出最多 365 天。

GET `/api/console/payments/{id}` 返回 `payment,refunds,journals,entries,attempts,tasks,conflicts,nextCursors`。退款、凭证、渠道尝试、任务、冲突各自最多 50 条，按 `createdAt DESC,id DESC` 排序；`entries` 返回本页 `journals` 的全部分录，不能把它当成整笔支付的全量账务。

`nextCursors` 的键为 `refunds,journals,attempts,tasks,conflicts`，值为下一页游标或 `null`。请求下一页时分别使用 `refundsCursor,journalsCursor,attemptsCursor,tasksCursor,conflictsCursor`，可同时传多类游标；平台角色可继续传 `merchantId`。游标签名绑定商户、支付和记录类型，1 小时有效，不能跨支付或跨类型复用；无效或过期返回 400。每次响应保持一致性快照，跨页是实时查证视图；查看翻页期间新产生的记录需刷新到首页。

POST `/api/console/reconciliation/upload` multipart 字段：`merchantId`（平台角色必填）、`channel,day,version,sha256,file`，最大 128 MiB / 100 万行。POST `/reconciliation/pull` JSON：`channel,day,version`（平台角色带 merchantId）。批次 `/batches/{id}`、原始文件 `/batches/{id}/original`、失败续跑 POST `/batches/{id}/retry`。隔离文件需修正并递增版本，不发布部分结果。

CSV UTF-8、无 BOM；第一行为渠道、商户、业务日、总笔数、总金额：

```csv
#merchantpay-bill-v1,MOCK_A,merchant-a,2026-10-18,1,10000
operation,aggregateId,status,amountMinor,feeMinor,currency,reference
PAYMENT,p0f_example,SUCCEEDED,10000,60,CNY,MOCK_A_reference
```

行结束符支持 LF 或 CRLF，末行可没有结束符；每行内容最多 1,024 个字符。孤立回车或字段内部的回车会作为格式错误隔离，不会删除字符后再匹配业务标识。原始文件保留。

本地快照冻结一次，按上海业务日内**创建**的支付 / 退款匹配。模拟渠道接收并持久化稳定 `businessCreatedAt`，与平台 createdAt 同口径，延迟发送或跨日恢复仍属原业务日；渠道实际处理时间单独保留。渠道完成日、迟到修正和真实文件格式留待后续适配。升级前的模拟渠道记录只能以原渠道时间回填，没有原业务时间时应保留既有证据并重新生成目标账单版本。

导出仅在发起人的当前权限和商户范围内可见 / 可下载，链接创建后 24 小时过期（410）；同商户其他用户也不能下载（404）。升级前未保存发起人的导出需重新生成。

下载审计归属于导出记录的实际商户；平台用户没有固定商户范围时，也不会写成无商户记录。未通过下载权限、有效期或完成状态检查的请求不写下载审计。历史缺少商户归属的审计本次不回填。
