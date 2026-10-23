// Generated from docs/api/openapi.json. Do not edit; run pnpm --dir sdk generate.
// Contract SHA-256: f3d0ac610c76797a6089f5f0f7de3848a0a4d96592dddb08160534336505449c
import type { components } from './models.ts';
import { MerchantTransport, type RequestOptions } from '../transport.ts';

export class MerchantClient extends MerchantTransport {
  createPayment(body: components["schemas"]["CreatePayment"], options?: RequestOptions): Promise<components["schemas"]["Payment"]> {
    return this.request({"operationId":"createPayment","method":"POST","path":"/payments","successStatus":202,"amountPattern":"^([1-9][0-9]{0,8}|1000000000)$"}, {  }, body, options);
  }

  getPayment(id: string, options?: RequestOptions): Promise<components["schemas"]["Payment"]> {
    return this.request({"operationId":"getPayment","method":"GET","path":"/payments/{id}","successStatus":200}, { id }, undefined, options);
  }

  requestClose(id: string, options?: RequestOptions): Promise<components["schemas"]["Payment"]> {
    return this.request({"operationId":"requestClose","method":"POST","path":"/payments/{id}/close","successStatus":202}, { id }, undefined, options);
  }

  createRefund(id: string, body: components["schemas"]["CreateRefund"], options?: RequestOptions): Promise<components["schemas"]["Refund"]> {
    return this.request({"operationId":"createRefund","method":"POST","path":"/payments/{id}/refunds","successStatus":202,"amountPattern":"^([1-9][0-9]{0,8}|1000000000)$"}, { id }, body, options);
  }

  getRefund(id: string, options?: RequestOptions): Promise<components["schemas"]["Refund"]> {
    return this.request({"operationId":"getRefund","method":"GET","path":"/refunds/{id}","successStatus":200}, { id }, undefined, options);
  }
}
