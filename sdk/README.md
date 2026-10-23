# MerchantPay Node.js 接入客户端

仅用于可信服务端，Node 24 以上。不要将商户 secret 或本 SDK 打包进浏览器；控制台继续使用 OIDC 会话和 CSRF。

从根目录运行项目 Node/pnpm：

```powershell
& scripts/pnpm.ps1 -PnpmArguments @('--dir','../sdk','install','--frozen-lockfile')
& scripts/pnpm.ps1 -PnpmArguments @('--dir','../sdk','verify')
& scripts/pnpm.ps1 -PnpmArguments @('--dir','../sdk','smoke')
```

`docs/api/openapi.json` 是合同源。`pnpm generate` 从合同生成模型和五个 operationId 方法；`check:generated` 只检查漂移，不自动修改文件。SDK 没有运行时依赖。

```typescript
import { MerchantClient, MerchantApiError } from '@merchantpay/node-sdk';

const client = new MerchantClient({
  baseUrl: 'https://payments.example.com/api/v1',
  keyId: process.env.MERCHANT_KEY_ID!,
  secret: process.env.MERCHANT_KEY_SECRET!,
});
const accepted = await client.createPayment({
  merchantOrderNo: 'your-stable-order-number',
  amountMinor: '10000', currency: 'CNY', channel: 'MOCK_A',
});
const current = await client.getPayment(accepted.id);
```

金额始终为分单位字符串。JSON 只序列化一次；签名覆盖实际 URL 路径/查询及实际发送的 UTF-8 字节，每次显式调用都使用新 nonce。HTTP 202 只表示受理，不表示支付成功。

SDK 不自动重试写入。`MerchantApiError` 保留 HTTP 状态、requestId、Retry-After、规范化 retryReason、rawRetryReason 和原业务号/资源 ID 的 recovery 上下文。网络错误、超时或 5xx 可能已受理；明确重试时保持原业务号及所有原参数，使用新 nonce。可重复受理恢复同一支付对象，再按 id 查证。当前合同没有按商户业务号 GET 的接口，不虚构该接口。

本地 smoke 对一个专用不存在的 ID 执行签名查询并核对 404，不打印凭据或业务明细，不创建金融交易。完整五个方法的原始签名、金额、重定向、超时、错误及生成漂移由真实回环 HTTP 测试覆盖。
