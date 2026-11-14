package dev.merchantpay.platform.history;

import dev.merchantpay.platform.common.Actor;
import dev.merchantpay.platform.common.PaymentBuckets;
import dev.merchantpay.platform.common.Signatures;
import dev.merchantpay.platform.outbox.ColdEventResolver;
import dev.merchantpay.platform.outbox.DomainEvent;
import dev.merchantpay.platform.outbox.EventDelivery;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
@ConditionalOnProperty(
    name = {
      "merchantpay.sharding.enabled",
      "merchantpay.history.archive-enabled",
      "merchantpay.history.retire-enabled"
    },
    havingValue = "true")
public final class HistoryColdEventResolver implements ColdEventResolver {
  private static final Actor REPLAY = new Actor("history-cold-replay", null, Set.of("OPS"));
  private final HistoryArchiveService archives;
  private final JsonMapper json;

  public HistoryColdEventResolver(HistoryArchiveService archives, JsonMapper json) {
    this.archives = archives;
    this.json = json;
  }

  @Override
  public Verified verify(JdbcTemplate source, DomainEvent event, String payload) {
    var locators =
        source.queryForList(
            "SELECT merchant_id,payment_id,logical_bucket "
                + "FROM v3_history_event_locators WHERE event_id=? AND state='COLD'",
            event.eventId());
    if (locators.size() != 1 || !event.merchantId().equals(locators.getFirst().get("merchant_id")))
      throw new EventDelivery.InvalidEvent("Cold event locator is absent or inconsistent");
    var row = locators.getFirst();
    String payment = (String) row.get("payment_id");
    int bucket = ((Number) row.get("logical_bucket")).intValue();
    if (PaymentBuckets.forPaymentId(payment) != bucket)
      throw new EventDelivery.InvalidEvent("Cold event bucket differs from payment");
    try {
      var fact = archives.readEvent(event.merchantId(), payment, event.eventId(), REPLAY);
      var document = json.readTree(fact.payloadJson());
      if (!document.hasNonNull("payload")
          || !document.get("payload").isString()
          || !payload.equals(document.get("payload").asString())
          || !document.hasNonNull("payloadHash")
          || !Signatures.sha256(payload).equals(document.get("payloadHash").asString()))
        throw new EventDelivery.InvalidEvent("Cold event differs from archived authority");
      return new Verified(document.get("payloadHash").asString(), bucket);
    } catch (EventDelivery.InvalidEvent invalid) {
      throw invalid;
    } catch (RuntimeException unavailable) {
      throw new EventDelivery.InvalidEvent("Cold event archive could not be verified");
    }
  }
}
