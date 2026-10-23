import { createHash, createHmac, randomBytes } from 'node:crypto';

export interface RequestOptions { signal?: AbortSignal }
export interface ClientOptions { baseUrl: string; keyId: string; secret: string; timeoutMs?: number }
export interface Operation { operationId: string; method: string; path: string; successStatus: number; amountPattern?: string }
export interface SignatureInput { secret: string; timestamp: string; nonce: string; method: string; rawPathAndQuery: string; body: string }

export function signRequest(input: SignatureInput): string {
  const hash = createHash('sha256').update(input.body, 'utf8').digest('hex');
  const canonical = [input.timestamp, input.nonce, input.method.toUpperCase(), input.rawPathAndQuery, hash].join('\n');
  return createHmac('sha256', input.secret).update(canonical, 'utf8').digest('hex');
}

export interface RecoveryContext {
  operationId: string;
  businessKey?: string;
  resourceId?: string;
}
interface ErrorDetails {
  status?: number;
  code: string;
  retryable: boolean;
  reconciliationRequired: boolean;
  requestId?: string | null;
  retryAfter?: string | null;
  retryReason?: string | null;
  rawRetryReason?: string | null;
  recovery: RecoveryContext;
}

export class MerchantApiError extends Error {
  readonly status?: number;
  readonly code: string;
  readonly retryable: boolean;
  readonly reconciliationRequired: boolean;
  readonly requestId: string | null;
  readonly retryAfter: string | null;
  readonly retryReason: string | null;
  readonly rawRetryReason: string | null;
  readonly recovery: Readonly<RecoveryContext>;

  constructor(message: string, details: ErrorDetails) {
    super(message);
    this.name = 'MerchantApiError';
    this.status = details.status;
    this.code = details.code;
    this.retryable = details.retryable;
    this.reconciliationRequired = details.reconciliationRequired;
    this.requestId = details.requestId ?? null;
    this.retryAfter = details.retryAfter ?? null;
    this.retryReason = details.retryReason ?? null;
    this.rawRetryReason = details.rawRetryReason ?? null;
    this.recovery = Object.freeze({ ...details.recovery });
  }
}

export class MerchantTransport {
  #baseUrl: string;
  #keyId: string;
  #secret: string;
  #timeoutMs: number;

  constructor(options: ClientOptions) {
    let base: URL;
    try { base = new URL(options.baseUrl); } catch { throw new TypeError('baseUrl must be an absolute HTTPS URL'); }
    const local = ['127.0.0.1', 'localhost', '[::1]'].includes(base.hostname);
    if ((base.protocol !== 'https:' && !(base.protocol === 'http:' && local)) || base.username || base.password || base.search || base.hash) {
      throw new TypeError('baseUrl must use HTTPS (HTTP only on loopback) without credentials, query or fragment');
    }
    if (!options.keyId || !options.secret) throw new TypeError('keyId and secret are required');
    this.#timeoutMs = options.timeoutMs ?? 20_000;
    if (!Number.isInteger(this.#timeoutMs) || this.#timeoutMs < 1 || this.#timeoutMs > 2_147_483_647) throw new TypeError('timeoutMs is out of range');
    this.#baseUrl = base.href.replace(/\/+$/, '');
    this.#keyId = options.keyId;
    this.#secret = options.secret;
  }

  protected async request<T>(operation: Operation, params: Record<string, string>, body?: unknown, options?: RequestOptions): Promise<T> {
    const path = operation.path.replace(/\{(\w+)\}/g, (_match, name: string) => {
      const value = params[name];
      if (typeof value !== 'string' || !value || value.length > 64 || value === '.' || value === '..') throw new TypeError(`${name} must be a nonempty id of at most 64 characters`);
      return encodeURIComponent(value);
    });
    // Serialize once: validation, recovery context, signature and HTTP use the same snapshot.
    const rawBody = body === undefined ? '' : JSON.stringify(body);
    if (rawBody === undefined) throw new TypeError('Expected a JSON object body');
    if (Buffer.byteLength(rawBody, 'utf8') > 65_536) throw new TypeError('Signed JSON must not exceed 64 KiB');
    const snapshot = rawBody ? JSON.parse(rawBody) : undefined;
    if (operation.amountPattern && (typeof snapshot?.amountMinor !== 'string' || !new RegExp(operation.amountPattern).test(snapshot.amountMinor))) {
      throw new TypeError('amountMinor must be a canonical positive integer string within the OpenAPI range');
    }
    const recovery: RecoveryContext = {
      operationId: operation.operationId,
      businessKey: snapshot?.merchantOrderNo ?? snapshot?.merchantRefundNo,
      resourceId: params.id,
    };
    const url = new URL(this.#baseUrl + path);
    const timestamp = Math.floor(Date.now() / 1000).toString();
    const nonce = randomBytes(24).toString('base64url');
    const signature = signRequest({ secret: this.#secret, timestamp, nonce, method: operation.method,
      rawPathAndQuery: url.pathname + url.search, body: rawBody });
    const headers: Record<string, string> = {
      'X-Key-Id': this.#keyId, 'X-Timestamp': timestamp, 'X-Nonce': nonce,
      'X-Signature': signature, 'X-Api-Version': '1.0', Accept: 'application/json',
    };
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    const timeout = AbortSignal.timeout(this.#timeoutMs);
    const signal = options?.signal ? AbortSignal.any([timeout, options.signal]) : timeout;
    let response: Response;
    let text: string;
    try {
      response = await fetch(url, { method: operation.method, headers,
        body: rawBody || undefined, signal, redirect: 'error' });
      text = await response.text();
    } catch {
      throw new MerchantApiError('Network error or timeout; reconcile using the original business key before an explicit retry', {
        code: 'NETWORK_ERROR', retryable: true, reconciliationRequired: operation.method !== 'GET', recovery,
      });
    }
    let data: unknown;
    try { data = JSON.parse(text); } catch { data = undefined; }
    const rawRetryReason = response.headers.get('X-Retry-Reason');
    const knownReasons = new Set(['API_INFLIGHT', 'QUOTA_CAPACITY', 'QUOTA_TIMEOUT', 'QUOTA_INTERRUPTED',
      'QUOTA_UNAVAILABLE', 'QUOTA_DATABASE', 'PAYMENT_CAPACITY', 'DATABASE_UNAVAILABLE', 'DATABASE_TRANSIENT', 'UNCLASSIFIED']);
    const metadata = {
      status: response.status, requestId: response.headers.get('X-Request-Id'),
      retryAfter: response.headers.get('Retry-After'), rawRetryReason,
      retryReason: response.status === 503 ? (rawRetryReason && knownReasons.has(rawRetryReason) ? rawRetryReason : 'UNCLASSIFIED') : rawRetryReason,
      recovery,
    };
    if (!response.ok) {
      const error = data && typeof data === 'object' ? data as Record<string, unknown> : undefined;
      throw new MerchantApiError(typeof error?.message === 'string' ? error.message : `HTTP request failed (${response.status})`, {
        ...metadata, code: typeof error?.code === 'string' ? error.code : `HTTP_${response.status}`,
        retryable: response.status === 429 || response.status >= 500,
        reconciliationRequired: operation.method !== 'GET' && response.status >= 500,
      });
    }
    if (response.status !== operation.successStatus || !data || typeof data !== 'object' || Array.isArray(data)) {
      throw new MerchantApiError('Invalid success response; reconcile using the original business key', {
        ...metadata, code: 'INVALID_RESPONSE', retryable: false, reconciliationRequired: operation.method !== 'GET',
      });
    }
    return data as T;
  }
}
