import assert from 'node:assert/strict';
import { readFile, writeFile } from 'node:fs/promises';
import { MerchantClient, MerchantApiError } from '../../sdk/dist/index.js';

const id = process.argv[2];
if (!/^[a-z0-9-]{1,24}$/.test(id ?? '')) throw new Error('A bounded restore ID is required');
const keys = JSON.parse(await readFile(new URL(`../../.data/restores/${id}/api-credentials.json`, import.meta.url), 'utf8'));
const clientFor = (merchant) => {
  const key = keys.find((entry) => entry.merchantId === merchant);
  if (!key) throw new Error('Restored merchant credential is unavailable');
  return new MerchantClient({ baseUrl: 'http://127.0.0.1:18082/api/v1', keyId: key.keyId, secret: key.secret });
};
const client = clientFor('merchant-a');
async function terminal(read) {
  for (let attempt = 0; attempt < 120; attempt++) {
    const current = await read();
    if (['SUCCEEDED', 'FAILED', 'CLOSED'].includes(current.status)) return current;
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  throw new Error('Restored application did not reach a terminal result');
}
const accepted = await client.createPayment({ merchantOrderNo: `restore-api-${id}`, amountMinor: '10000', currency: 'CNY', channel: 'MOCK_A', notificationUrl: 'http://127.0.0.1:19092/merchant-webhook' });
const paid = await terminal(() => client.getPayment(accepted.id));
assert.equal(paid.status, 'SUCCEEDED');
assert.equal(paid.amountMinor, '10000');
const acceptedRefund = await client.createRefund(paid.id, { merchantRefundNo: `restore-refund-${id}`, amountMinor: '3000' });
const refunded = await terminal(() => client.getRefund(acceptedRefund.id));
assert.equal(refunded.status, 'SUCCEEDED');
assert.equal(refunded.amountMinor, '3000');
let isolated = false;
try { await clientFor('merchant-b').getPayment(paid.id); }
catch (error) { if (!(error instanceof MerchantApiError) || error.status !== 404) throw error; isolated = true; }
assert.equal(isolated, true);
await writeFile(new URL(`../../.work/release/restore-${id}-api-http.json`, import.meta.url), JSON.stringify({ at: new Date().toISOString(), signedPaymentSucceeded: true, signedRefundSucceeded: true, crossMerchant404: true, paymentAmountMinor: '10000', refundAmountMinor: '3000', sourceFinancialWrites: 0, restoredOnly: true, referenceR1Certified: false }, null, 2));
console.log('Restored merchant keys authenticated payment, refund and cross-merchant rejection on isolated port 18082.');
