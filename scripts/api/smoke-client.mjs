import { readFile } from 'node:fs/promises';
import { MerchantClient, MerchantApiError } from '../../sdk/dist/index.js';

const keys = JSON.parse(await readFile(new URL('../../.data/platform/local-api-credentials.json', import.meta.url), 'utf8'));
const key = keys.find((entry) => entry.merchantId === 'merchant-a');
if (!key) throw new Error('Local merchant credential is unavailable');
const client = new MerchantClient({ baseUrl: 'http://127.0.0.1:8080/api/v1', keyId: key.keyId, secret: key.secret });
try {
  await client.getPayment('sdk-readonly-expected-missing');
  throw new Error('The isolated missing-object sentinel unexpectedly exists');
} catch (error) {
  if (!(error instanceof MerchantApiError) || error.status !== 404) throw error;
  console.log(JSON.stringify({ realPlatformHttp: true, validSignatureAndTenantLookup: true, expectedStatus: 404, financialWrites: 0 }));
}
