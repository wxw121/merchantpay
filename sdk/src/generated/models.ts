// Generated from docs/api/openapi.json. Do not edit; run pnpm --dir sdk generate.
export interface paths {
    "/payments": {
        parameters: {
            query?: never;
            header: {
                "X-Timestamp": string;
                "X-Nonce": string;
                "X-Signature": string;
                "X-Api-Version"?: "1.0";
            };
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** @description 受理支付；相同业务号和参数返回原对象 */
        post: operations["createPayment"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/payments/{id}": {
        parameters: {
            query?: never;
            header: {
                "X-Timestamp": string;
                "X-Nonce": string;
                "X-Signature": string;
                "X-Api-Version"?: "1.0";
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        /** @description 读取本商户支付权威状态 */
        get: operations["getPayment"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/payments/{id}/close": {
        parameters: {
            query?: never;
            header: {
                "X-Timestamp": string;
                "X-Nonce": string;
                "X-Signature": string;
                "X-Api-Version"?: "1.0";
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** @description 请求关闭并等待渠道证据，可能发现已成功 */
        post: operations["requestClose"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/payments/{id}/refunds": {
        parameters: {
            query?: never;
            header: {
                "X-Timestamp": string;
                "X-Nonce": string;
                "X-Signature": string;
                "X-Api-Version"?: "1.0";
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** @description 受理退款并预占额度；未知保留预占 */
        post: operations["createRefund"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/refunds/{id}": {
        parameters: {
            query?: never;
            header: {
                "X-Timestamp": string;
                "X-Nonce": string;
                "X-Signature": string;
                "X-Api-Version"?: "1.0";
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        /** @description 读取本商户退款权威状态 */
        get: operations["getRefund"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
}
export type webhooks = Record<string, never>;
export interface components {
    schemas: {
        CreatePayment: {
            merchantOrderNo: string;
            /** @example 10000 */
            amountMinor: string;
            /** @enum {string} */
            currency: "CNY";
            /** @enum {string} */
            channel: "MOCK_A" | "MOCK_B";
            /**
             * Format: uri
             * @description HTTPS 443 公网域名；本地模拟地址仅由 local 配置放行
             */
            notificationUrl?: string | null;
        };
        CreateRefund: {
            merchantRefundNo: string;
            /** @example 10000 */
            amountMinor: string;
        };
        Payment: {
            id?: string;
            merchantId?: string;
            merchantOrderNo?: string;
            /** @description CNY 分，十进制整数字符串 */
            amountMinor?: string;
            /** @description CNY 分，十进制整数字符串 */
            feeMinor?: string;
            /** @enum {string} */
            currency?: "CNY";
            /** @enum {string} */
            status?: "PROCESSING" | "UNKNOWN" | "CLOSE_PENDING" | "SUCCEEDED" | "FAILED" | "CLOSED";
            /** @description CNY 分，十进制整数字符串 */
            refundedMinor?: string;
            /** @description CNY 分，十进制整数字符串 */
            reservedMinor?: string;
            /** @description CNY 分，十进制整数字符串 */
            refundedFeeMinor?: string;
            /** @enum {string} */
            channel?: "MOCK_A" | "MOCK_B";
            channelReference?: string | null;
            notificationUrl?: string | null;
            /** Format: date-time */
            createdAt?: string;
            /** Format: date-time */
            updatedAt?: string;
        };
        Refund: {
            id?: string;
            paymentId?: string;
            merchantId?: string;
            merchantRefundNo?: string;
            /** @description CNY 分，十进制整数字符串 */
            amountMinor?: string;
            /** @description CNY 分，十进制整数字符串 */
            refundedFeeMinor?: string;
            /** @enum {string} */
            status?: "PROCESSING" | "UNKNOWN" | "SUCCEEDED" | "FAILED";
            channel?: string;
            channelReference?: string | null;
            /** Format: date-time */
            createdAt?: string;
            /** Format: date-time */
            updatedAt?: string;
        };
        Error: {
            code: string;
            message: string;
        };
    };
    responses: never;
    parameters: never;
    requestBodies: never;
    headers: never;
    pathItems: never;
}
export type $defs = Record<string, never>;
export interface operations {
    createPayment: {
        parameters: {
            query?: never;
            header: {
                "X-Timestamp": string;
                "X-Nonce": string;
                "X-Signature": string;
                "X-Api-Version"?: "1.0";
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CreatePayment"];
            };
        };
        responses: {
            /** @description 受理支付；相同业务号和参数返回原对象 */
            202: {
                headers: {
                    /** @description 日志关联标识 */
                    "X-Request-Id"?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Payment"];
                };
            };
            /** @description 参数无效 */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Error"];
                };
            };
            /** @description 认证无效 */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Error"];
                };
            };
            /** @description 权限不足 */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Error"];
                };
            };
            /** @description 不存在或不属于该商户 */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Error"];
                };
            };
            /** @description 幂等、重放、额度或状态冲突 */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Error"];
                };
            };
            /** @description 超过配额 */
            429: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Error"];
                };
            };
            /** @description 可能已提交；原业务号查询及退避重试 */
            503: {
                headers: {
                    /** @description Retry delay in seconds; reuse the original business key with a new nonce. */
                    "Retry-After"?: string;
                    /** @description Fixed retry classification; unknown or absent values are UNCLASSIFIED. */
                    "X-Retry-Reason"?: "API_INFLIGHT" | "QUOTA_CAPACITY" | "QUOTA_TIMEOUT" | "QUOTA_INTERRUPTED" | "QUOTA_UNAVAILABLE" | "QUOTA_DATABASE" | "PAYMENT_CAPACITY" | "DATABASE_UNAVAILABLE" | "DATABASE_TRANSIENT" | "UNCLASSIFIED";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Error"];
                };
            };
            /** @description 未分类5xx也可能已提交；先以原业务号查单，再退避重试。 */
            default: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    getPayment: {
        parameters: {
            query?: never;
            header: {
                "X-Timestamp": string;
                "X-Nonce": string;
                "X-Signature": string;
                "X-Api-Version"?: "1.0";
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description 读取本商户支付权威状态 */
            200: {
                headers: {
                    /** @description 日志关联标识 */
                    "X-Request-Id"?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Payment"];
                };
            };
            /** @description 参数无效 */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Error"];
                };
            };
            /** @description 认证无效 */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Error"];
                };
            };
            /** @description 权限不足 */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Error"];
                };
            };
            /** @description 不存在或不属于该商户 */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Error"];
                };
            };
            /** @description 幂等、重放、额度或状态冲突 */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Error"];
                };
            };
            /** @description 超过配额 */
            429: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Error"];
                };
            };
            /** @description 可能已提交；原业务号查询及退避重试 */
            503: {
                headers: {
                    /** @description Retry delay in seconds; reuse the original business key with a new nonce. */
                    "Retry-After"?: string;
                    /** @description Fixed retry classification; unknown or absent values are UNCLASSIFIED. */
                    "X-Retry-Reason"?: "API_INFLIGHT" | "QUOTA_CAPACITY" | "QUOTA_TIMEOUT" | "QUOTA_INTERRUPTED" | "QUOTA_UNAVAILABLE" | "QUOTA_DATABASE" | "PAYMENT_CAPACITY" | "DATABASE_UNAVAILABLE" | "DATABASE_TRANSIENT" | "UNCLASSIFIED";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Error"];
                };
            };
            /** @description 未分类5xx也可能已提交；先以原业务号查单，再退避重试。 */
            default: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    requestClose: {
        parameters: {
            query?: never;
            header: {
                "X-Timestamp": string;
                "X-Nonce": string;
                "X-Signature": string;
                "X-Api-Version"?: "1.0";
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description 请求关闭并等待渠道证据，可能发现已成功 */
            202: {
                headers: {
                    /** @description 日志关联标识 */
                    "X-Request-Id"?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Payment"];
                };
            };
            /** @description 参数无效 */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Error"];
                };
            };
            /** @description 认证无效 */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Error"];
                };
            };
            /** @description 权限不足 */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Error"];
                };
            };
            /** @description 不存在或不属于该商户 */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Error"];
                };
            };
            /** @description 幂等、重放、额度或状态冲突 */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Error"];
                };
            };
            /** @description 超过配额 */
            429: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Error"];
                };
            };
            /** @description 可能已提交；原业务号查询及退避重试 */
            503: {
                headers: {
                    /** @description Retry delay in seconds; reuse the original business key with a new nonce. */
                    "Retry-After"?: string;
                    /** @description Fixed retry classification; unknown or absent values are UNCLASSIFIED. */
                    "X-Retry-Reason"?: "API_INFLIGHT" | "QUOTA_CAPACITY" | "QUOTA_TIMEOUT" | "QUOTA_INTERRUPTED" | "QUOTA_UNAVAILABLE" | "QUOTA_DATABASE" | "PAYMENT_CAPACITY" | "DATABASE_UNAVAILABLE" | "DATABASE_TRANSIENT" | "UNCLASSIFIED";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Error"];
                };
            };
            /** @description 未分类5xx也可能已提交；先以原业务号查单，再退避重试。 */
            default: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    createRefund: {
        parameters: {
            query?: never;
            header: {
                "X-Timestamp": string;
                "X-Nonce": string;
                "X-Signature": string;
                "X-Api-Version"?: "1.0";
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CreateRefund"];
            };
        };
        responses: {
            /** @description 受理退款并预占额度；未知保留预占 */
            202: {
                headers: {
                    /** @description 日志关联标识 */
                    "X-Request-Id"?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Refund"];
                };
            };
            /** @description 参数无效 */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Error"];
                };
            };
            /** @description 认证无效 */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Error"];
                };
            };
            /** @description 权限不足 */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Error"];
                };
            };
            /** @description 不存在或不属于该商户 */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Error"];
                };
            };
            /** @description 幂等、重放、额度或状态冲突 */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Error"];
                };
            };
            /** @description 超过配额 */
            429: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Error"];
                };
            };
            /** @description 可能已提交；原业务号查询及退避重试 */
            503: {
                headers: {
                    /** @description Retry delay in seconds; reuse the original business key with a new nonce. */
                    "Retry-After"?: string;
                    /** @description Fixed retry classification; unknown or absent values are UNCLASSIFIED. */
                    "X-Retry-Reason"?: "API_INFLIGHT" | "QUOTA_CAPACITY" | "QUOTA_TIMEOUT" | "QUOTA_INTERRUPTED" | "QUOTA_UNAVAILABLE" | "QUOTA_DATABASE" | "PAYMENT_CAPACITY" | "DATABASE_UNAVAILABLE" | "DATABASE_TRANSIENT" | "UNCLASSIFIED";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Error"];
                };
            };
            /** @description 未分类5xx也可能已提交；先以原业务号查单，再退避重试。 */
            default: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    getRefund: {
        parameters: {
            query?: never;
            header: {
                "X-Timestamp": string;
                "X-Nonce": string;
                "X-Signature": string;
                "X-Api-Version"?: "1.0";
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description 读取本商户退款权威状态 */
            200: {
                headers: {
                    /** @description 日志关联标识 */
                    "X-Request-Id"?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Refund"];
                };
            };
            /** @description 参数无效 */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Error"];
                };
            };
            /** @description 认证无效 */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Error"];
                };
            };
            /** @description 权限不足 */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Error"];
                };
            };
            /** @description 不存在或不属于该商户 */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Error"];
                };
            };
            /** @description 幂等、重放、额度或状态冲突 */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Error"];
                };
            };
            /** @description 超过配额 */
            429: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Error"];
                };
            };
            /** @description 可能已提交；原业务号查询及退避重试 */
            503: {
                headers: {
                    /** @description Retry delay in seconds; reuse the original business key with a new nonce. */
                    "Retry-After"?: string;
                    /** @description Fixed retry classification; unknown or absent values are UNCLASSIFIED. */
                    "X-Retry-Reason"?: "API_INFLIGHT" | "QUOTA_CAPACITY" | "QUOTA_TIMEOUT" | "QUOTA_INTERRUPTED" | "QUOTA_UNAVAILABLE" | "QUOTA_DATABASE" | "PAYMENT_CAPACITY" | "DATABASE_UNAVAILABLE" | "DATABASE_TRANSIENT" | "UNCLASSIFIED";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Error"];
                };
            };
            /** @description 未分类5xx也可能已提交；先以原业务号查单，再退避重试。 */
            default: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
}
