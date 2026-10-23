import assert from 'node:assert/strict';
import { createHash, createHmac } from 'node:crypto';
import { createServer, type IncomingMessage, type ServerResponse } from 'node:http';
import { once } from 'node:events';
import { inspect } from 'node:util';
import { test } from 'node:test';
import { MerchantClient, MerchantApiError } from '../src/index.ts';
import { signRequest } from '../src/transport.ts';

const secret = 'test-only-secret';
const payment = { id: 'p_test', merchantId: 'merchant-test', merchantOrderNo: '订单-1', amountMinor: '1000000000',
  feeMinor: '6000000', currency: 'CNY', status: 'PROCESSING', refundedMinor: '0', reservedMinor: '0',
  refundedFeeMinor: '0', channel: 'MOCK_A', channelReference: null, notificationUrl: null,
  createdAt: '2026-10-19T00:00:00Z', updatedAt: '2026-10-19T00:00:00Z' };
const refund = { id: 'r_test', paymentId: payment.id, merchantId: payment.merchantId,
  merchantRefundNo: 'refund-1', amountMinor: '1', refundedFeeMinor: '0', status: 'PROCESSING',
  channel: 'MOCK_A', channelReference: null, createdAt: payment.createdAt, updatedAt: payment.updatedAt };
const createBody = { merchantOrderNo: '订单-1', amountMinor: '1000000000', currency: 'CNY', channel: 'MOCK_A' } as const;

async function server(handler: (req: IncomingMessage, res: ServerResponse, body: string) => void | Promise<void>) {
  const app = createServer(async (req, res) => {
    const chunks: Buffer[] = [];
    for await (const chunk of req) chunks.push(Buffer.from(chunk));
    try { await handler(req, res, Buffer.concat(chunks).toString('utf8')); }
    catch (error) { res.writeHead(500); res.end(JSON.stringify({ code: 'TEST_SERVER_ASSERTION', message: String(error) })); }
  });
  app.listen(0, '127.0.0.1');
  await once(app, 'listening');
  const address = app.address();
  assert.ok(address && typeof address !== 'string');
  return {
    baseUrl: `http://127.0.0.1:${address.port}/api/v1`,
    async close() { app.closeAllConnections(); await new Promise<void>((resolve, reject) => app.close((error) => error ? reject(error) : resolve())); },
  };
}
function client(baseUrl: string, timeoutMs = 2000) { return new MerchantClient({ baseUrl, keyId: 'test-key', secret, timeoutMs }); }
function reply(res: ServerResponse, status: number, body: unknown) {
  res.writeHead(status, { 'content-type': 'application/json', 'x-request-id': 'request-test' });
  res.end(JSON.stringify(body));
}

test('unknown retry reason is classified while preserving the raw server header', async () => {
  const api = await server((_req, res) => {
    res.setHeader('X-Retry-Reason', 'NEW_SERVER_REASON');
    res.setHeader('Retry-After', '1');
    reply(res, 503, { code: 'RETRY_WITH_SAME_BUSINESS_KEY', message: 'Busy' });
  });
  try {
    await assert.rejects(client(api.baseUrl).getPayment('p_test'), (error: unknown) => {
      assert.ok(error instanceof MerchantApiError);
      assert.equal(error.retryReason, 'UNCLASSIFIED');
      assert.equal(error.rawRetryReason, 'NEW_SERVER_REASON');
      assert.equal(error.retryAfter, '1');
      return true;
    });
  } finally { await api.close(); }
});

test('five-line HMAC covers raw query order, UTF-8 JSON and has no trailing newline', () => {
  assert.equal(signRequest({ secret, timestamp: '1792400000', nonce: 'abcdefghijklmnop', method: 'POST',
    rawPathAndQuery: '/api/v1/payments?tag=a%2Bb&tag=a+b', body: JSON.stringify(createBody) }),
  'ed23b9457c208570eef13fb259a5daa3ec1b788d0b3033110c4c66d2b72b1a65');
});

test('generated operations send exactly signed bytes with fresh nonce and string money', async () => {
  const received: { path: string; method: string; body: string; nonce: string }[] = [];
  const api = await server((req, res, body) => {
    const timestamp = String(req.headers['x-timestamp']);
    const nonce = String(req.headers['x-nonce']);
    assert.match(timestamp, /^\d{10}$/);
    assert.match(nonce, /^[a-zA-Z0-9_-]{16,100}$/);
    assert.equal(req.headers['x-key-id'], 'test-key');
    assert.equal(req.headers['x-api-version'], '1.0');
    const hash = createHash('sha256').update(body, 'utf8').digest('hex');
    const expected = createHmac('sha256', secret).update(`${timestamp}\n${nonce}\n${req.method}\n${req.url}\n${hash}`).digest('hex');
    assert.equal(req.headers['x-signature'], expected);
    received.push({ path: req.url!, method: req.method!, body, nonce });
    reply(res, req.method === 'GET' ? 200 : 202, req.url!.includes('refunds') ? refund : payment);
  });
  try {
    const sdk = client(api.baseUrl + '/');
    let serializations = 0;
    const body = { ...createBody, toJSON() { serializations++; return createBody; } };
    assert.equal((await sdk.createPayment(body)).amountMinor, '1000000000');
    await sdk.createPayment(createBody);
    await sdk.getPayment('p/a? 空');
    await sdk.requestClose('p_test');
    await sdk.createRefund('p_test', { merchantRefundNo: 'refund-1', amountMinor: '1' });
    await sdk.getRefund('r_test');
    assert.equal(serializations, 1, 're-serializing after signing can change the request bytes');
    assert.deepEqual(received.map(({ path, method }) => [method, path]), [
      ['POST', '/api/v1/payments'], ['POST', '/api/v1/payments'],
      ['GET', '/api/v1/payments/p%2Fa%3F%20%E7%A9%BA'], ['POST', '/api/v1/payments/p_test/close'],
      ['POST', '/api/v1/payments/p_test/refunds'], ['GET', '/api/v1/refunds/r_test'],
    ]);
    assert.equal(received[0]!.body, '{"merchantOrderNo":"订单-1","amountMinor":"1000000000","currency":"CNY","channel":"MOCK_A"}');
    assert.equal(received[1]!.body, received[0]!.body, 'same business key and parameters are preserved on explicit repeat');
    assert.equal(received[2]!.body, '');
    assert.equal(received[3]!.body, '');
    assert.equal(new Set(received.map((r) => r.nonce)).size, received.length);
    assert.ok(!inspect(sdk, { showHidden: true }).includes(secret));
  } finally { await api.close(); }
});

test('money validation rejects numbers, noncanonical strings and toJSON substitution before HTTP', async () => {
  let requests = 0;
  const api = await server((_req, res) => { requests++; reply(res, 202, payment); });
  try {
    const sdk = client(api.baseUrl);
    for (const amountMinor of [1, 0, '0', '01', '1.0', '1e2', '+1', '-1', ' 1', '1000000001', null, undefined]) {
      // @ts-expect-error Intentionally exercise the JavaScript runtime boundary.
      await assert.rejects(sdk.createPayment({ ...createBody, amountMinor }), /amountMinor/);
      // @ts-expect-error Intentionally exercise the JavaScript runtime boundary.
      await assert.rejects(sdk.createRefund('p_test', { merchantRefundNo: 'r-1', amountMinor }), /amountMinor/);
    }
    const substituted = { ...createBody, toJSON: () => ({ ...createBody, amountMinor: 1 }) };
    await assert.rejects(sdk.createPayment(substituted), /amountMinor/);
    await assert.rejects(sdk.createPayment({ ...createBody, notificationUrl: 'x'.repeat(65536) }), /64 KiB/);
    assert.equal(requests, 0);
  } finally { await api.close(); }
});

test('503 exposes reason, Retry-After and original business key without automatic retry', async () => {
  let requests = 0;
  const api = await server((_req, res) => {
    requests++;
    res.setHeader('X-Retry-Reason', 'PAYMENT_CAPACITY');
    res.setHeader('Retry-After', '1');
    reply(res, 503, { code: 'RETRY_WITH_SAME_BUSINESS_KEY', message: 'busy' });
  });
  try {
    await assert.rejects(client(api.baseUrl).createPayment(createBody), (error: unknown) => {
      assert.ok(error instanceof MerchantApiError);
      assert.equal(error.status, 503);
      assert.equal(error.code, 'RETRY_WITH_SAME_BUSINESS_KEY');
      assert.equal(error.retryReason, 'PAYMENT_CAPACITY');
      assert.equal(error.retryAfter, '1');
      assert.equal(error.requestId, 'request-test');
      assert.equal(error.retryable, true);
      assert.equal(error.reconciliationRequired, true);
      assert.deepEqual(error.recovery, { operationId: 'createPayment', businessKey: '订单-1', resourceId: undefined });
      assert.ok(!inspect(error, { showHidden: true }).includes(secret));
      return true;
    });
    assert.equal(requests, 1);
  } finally { await api.close(); }
});

test('unknown 503 reasons remain diagnostic and HTML errors preserve HTTP status', async () => {
  const api = await server((_req, res) => { res.writeHead(503, { 'x-retry-reason': 'FUTURE_REASON' }); res.end('<html>unavailable</html>'); });
  try {
    await assert.rejects(client(api.baseUrl).getPayment('p_test'), (error: unknown) => {
      assert.ok(error instanceof MerchantApiError);
      assert.equal(error.status, 503);
      assert.equal(error.code, 'HTTP_503');
      assert.equal(error.retryReason, 'UNCLASSIFIED');
      assert.equal(error.rawRetryReason, 'FUTURE_REASON');
      assert.equal(error.retryAfter, null);
      assert.equal(error.reconciliationRequired, false);
      assert.equal(error.retryable, true);
      return true;
    });
  } finally { await api.close(); }
});

test('empty auth and quota errors do not imply an accepted payment', async () => {
  let status = 401;
  const api = await server((_req, res) => { res.writeHead(status, { 'retry-after': '1' }); res.end(); });
  try {
    for (status of [401, 403, 404, 409, 429]) {
      await assert.rejects(client(api.baseUrl).createPayment(createBody), (error: unknown) => {
        assert.ok(error instanceof MerchantApiError);
        assert.equal(error.status, status);
        assert.equal(error.retryable, status === 429);
        assert.equal(error.reconciliationRequired, false);
        return true;
      });
    }
  } finally { await api.close(); }
});

test('network uncertainty retains refund key and payment id for reconciliation without retry', async () => {
  let requests = 0;
  const api = await server((req) => { requests++; req.socket.destroy(); });
  try {
    await assert.rejects(client(api.baseUrl).createRefund('p_test', { merchantRefundNo: 'refund-1', amountMinor: '1' }), (error: unknown) => {
      assert.ok(error instanceof MerchantApiError);
      assert.equal(error.code, 'NETWORK_ERROR');
      assert.equal(error.status, undefined);
      assert.equal(error.retryable, true);
      assert.equal(error.reconciliationRequired, true);
      assert.deepEqual(error.recovery, { operationId: 'createRefund', businessKey: 'refund-1', resourceId: 'p_test' });
      return true;
    });
    assert.equal(requests, 1);
  } finally { await api.close(); }
});

test('timeout and invalid success JSON report possible acceptance', async () => {
  let mode = 'invalid-json';
  const api = await server((_req, res) => { if (mode === 'invalid-json') { res.writeHead(202); res.end('{'); } });
  try {
    await assert.rejects(client(api.baseUrl).createPayment(createBody), (error: unknown) => {
      assert.ok(error instanceof MerchantApiError);
      assert.equal(error.code, 'INVALID_RESPONSE');
      assert.equal(error.status, 202);
      assert.equal(error.reconciliationRequired, true);
      return true;
    });
    mode = 'timeout';
    await assert.rejects(client(api.baseUrl, 30).requestClose('p_test'), (error: unknown) => {
      assert.ok(error instanceof MerchantApiError);
      assert.equal(error.code, 'NETWORK_ERROR');
      assert.equal(error.reconciliationRequired, true);
      assert.equal(error.recovery.resourceId, 'p_test');
      return true;
    });
  } finally { await api.close(); }
});

test('redirect is never followed with merchant authentication', async () => {
  let leaked = false;
  const destination = await server((_req, res) => { leaked = true; reply(res, 200, payment); });
  const api = await server((_req, res) => { res.writeHead(307, { location: destination.baseUrl + '/payments' }); res.end(); });
  try {
    await assert.rejects(client(api.baseUrl).createPayment(createBody), MerchantApiError);
    assert.equal(leaked, false);
  } finally { await api.close(); await destination.close(); }
});

test('base URL and path parameters cannot redirect credentials to insecure or unexpected paths', async () => {
  for (const baseUrl of ['http://example.com/api/v1', 'ftp://example.com/api/v1', 'https://user:pass@example.com/api/v1',
    'https://example.com/api/v1?secret=x', 'https://example.com/api/v1#fragment']) {
    assert.throws(() => client(baseUrl), /baseUrl/);
  }
  const sdk = client('http://127.0.0.1:1/api/v1');
  for (const id of ['', '.', '..', 'x'.repeat(65)]) await assert.rejects(sdk.getPayment(id), /id/);
});
