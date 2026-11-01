package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;

import dev.merchantpay.platform.common.*;
import dev.merchantpay.platform.finance.BalanceService;
import java.time.Clock;
import java.util.Set;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import tools.jackson.databind.json.JsonMapper;

class V2BalanceTest extends FinanceFixture {
  BalanceService balance;

  @BeforeEach
  void schema() {
    ScriptUtils.executeSqlScript(
        schemaConnection,
        new ClassPathResource("db/migration/V21__projection_and_balance_checkpoints.sql"));
    balance = new BalanceService(jdbc, tx, Clock.systemUTC(), JsonMapper.builder().build());
  }

  @Test
  void balancedJournalHasExactWitnessAndDoesNotFreeze() {
    paid("check", "10000");
    var result = balance.checkpoint("merchant-a", "verify", proposer);
    assertThat(result.get("status")).isEqualTo("CHECKED");
    assertThat(result.get("sourceHash").toString()).hasSize(64);
    balance.assertUnheld("merchant-a");
  }

  @Test
  void incompleteJournalFreezesAndOnlyNewVerifiedScanClearsOwnHold() {
    var p = paid("broken", "10000");
    jdbc.update(
        "UPDATE ledger_entries SET amount_minor=amount_minor+1 WHERE payment_id=? AND account_code='CHANNEL_RECEIVABLE'",
        p.id());
    assertThat(balance.checkpoint("merchant-a", "verify", proposer).get("status"))
        .isEqualTo("FAILED");
    assertThatThrownBy(() -> balance.assertUnheld("merchant-a")).isInstanceOf(ApiException.class);
    jdbc.update(
        "UPDATE ledger_entries SET amount_minor=amount_minor-1 WHERE payment_id=? AND account_code='CHANNEL_RECEIVABLE'",
        p.id());
    balance.checkpoint("merchant-a", "repaired verification", reviewer);
    balance.assertUnheld("merchant-a");
    assertThat(count("balance_checkpoints")).isEqualTo(2);
  }

  @Test
  void holdBlocksSettlementSourcesAndCrossTenantChecksFail() {
    paid("held", "10000");
    jdbc.update(
        "INSERT INTO merchant_finance_holds VALUES ('merchant-a','BALANCE_INTEGRITY','fixture',true,'broken',CURRENT_TIMESTAMP)");
    var settlements =
        new dev.merchantpay.platform.finance.SettlementService(
            support,
            new dev.merchantpay.platform.ledger.LedgerService(jdbc),
            new dev.merchantpay.platform.outbox.OutboxService(jdbc, Clock.systemUTC()));
    settlements.setBalances(balance);
    assertThatThrownBy(
            () -> settlements.create("merchant-a", java.time.Instant.now(), "settle", proposer))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () -> balance.list("merchant-a", new Actor("other", "merchant-b", Set.of("MERCHANT"))))
        .isInstanceOf(ApiException.class);
  }
}
