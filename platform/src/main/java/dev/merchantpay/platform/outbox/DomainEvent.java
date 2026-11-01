package dev.merchantpay.platform.outbox;

public record DomainEvent(
    int schemaVersion,
    String eventId,
    String eventType,
    String aggregateId,
    String merchantId,
    long aggregateVersion,
    String occurredAt,
    String traceId) {}
