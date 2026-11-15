package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;

import dev.merchantpay.platform.common.*;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AdjustmentServiceTest extends FinanceFixture {
  @Test
  void adjustmentRequiresIndependentApprovalAndPostsOnceWithoutChangingPayment() {
    var payment = paid("adjust", "10000");
    String caseId = investigated(payment.id());
    var draft = adjustments.propose(caseId, 2, "INCREASE", "100", "渠道手续费差异", proposer);
    String id = (String) draft.get("id");
    assertThatThrownBy(() -> adjustments.review(id, 1, true, "同意", proposer))
        .isInstanceOf(ApiException.class);
    adjustments.review(id, 1, true, "已复核", reviewer);
    adjustments.execute(id, 1, "执行已批准调账", proposer);
    adjustments.execute(id, 1, "重试", proposer);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_journals WHERE source_type='ADJUSTMENT'", Long.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT SUM(CASE WHEN side='DEBIT' THEN amount_minor ELSE -amount_minor END) FROM ledger_entries WHERE journal_id IN (SELECT id FROM ledger_journals WHERE source_type='ADJUSTMENT')",
                Long.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT amount_minor FROM payments WHERE id=?", Long.class, payment.id()))
        .isEqualTo(10000);
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM finance_cases WHERE id=?", String.class, caseId))
        .isEqualTo("RESOLVED");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_tasks WHERE kind='ADJUSTMENT_EXECUTED'", Long.class))
        .isZero();
  }

  @Test
  void editingProposalAndCaseEvidenceInvalidatesApproval() {
    var payment = paid("edit", "1000");
    String caseId = investigated(payment.id());
    String id =
        (String) adjustments.propose(caseId, 2, "DECREASE", "10", "修正差异", proposer).get("id");
    adjustments.review(id, 1, true, "已复核", reviewer);
    adjustments.revise(id, 1, "DECREASE", "20", "更新证据", proposer);
    assertThatThrownBy(() -> adjustments.execute(id, 1, "执行", proposer))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> adjustments.execute(id, 2, "执行", proposer))
        .isInstanceOf(ApiException.class);
    adjustments.review(id, 2, true, "重新复核", reviewer);
    adjustments.investigate(caseId, 2, proposer.id(), payment.id(), "新渠道证据", proposer);
    assertThatThrownBy(() -> adjustments.execute(id, 2, "执行", proposer))
        .isInstanceOf(ApiException.class);
    assertThat(count("finance_reviews")).isEqualTo(2);
  }

  @Test
  void adminAndForeignMerchantCannotMutateOrReadFinance() {
    String issue = issue(paid("scope", "1000").id());
    assertThatThrownBy(() -> adjustments.openCase(issue, new Actor("admin", null, Set.of("ADMIN"))))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () ->
                adjustments.openCase(issue, new Actor("foreign", "merchant-b", Set.of("FINANCE"))))
        .isInstanceOf(ApiException.class);
    String caseId = (String) adjustments.openCase(issue, proposer).get("id");
    assertThatThrownBy(
            () ->
                adjustments.caseDetail(
                    caseId, new Actor("foreign", "merchant-b", Set.of("MERCHANT"))))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () -> adjustments.caseDetail(caseId, new Actor("unbound", null, Set.of("MERCHANT"))))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () -> adjustments.cases(null, new Actor("unbound", null, Set.of("MERCHANT"))))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void requiresFinalEvidenceInvestigationAndValidRoot() {
    String root = paid("roots", "1000").id();
    String issue = issue(root);
    jdbc.update("UPDATE recon_batches SET status='PROCESSING' WHERE id=?", issue);
    assertThatThrownBy(() -> adjustments.openCase(issue, proposer))
        .isInstanceOf(ApiException.class);
    jdbc.update("UPDATE recon_batches SET status='COMPLETE' WHERE id=?", issue);
    String caseId = (String) adjustments.openCase(issue, proposer).get("id");
    assertThatThrownBy(() -> adjustments.propose(caseId, 1, "INCREASE", "10", "原因", proposer))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> adjustments.investigate(caseId, 1, "owner", "unknown", "证据", proposer))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () ->
                adjustments.investigate(
                    caseId, 1, "owner", paid("different-root", "1000").id(), "证据", proposer))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void approvedNoAdjustmentClosesCaseWithoutInventingJournal() {
    String caseId = investigated(paid("no-adjustment", "1000").id());
    String id =
        (String) adjustments.propose(caseId, 2, "NONE", "0", "渠道重复行，无需调账", proposer).get("id");
    adjustments.review(id, 1, true, "复核原始流水", reviewer);
    adjustments.execute(id, 1, "确认关闭", proposer);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_journals WHERE source_type='ADJUSTMENT'", Long.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM finance_cases WHERE id=?", String.class, caseId))
        .isEqualTo("RESOLVED");
  }

  @Test
  void eventFailureRollsBackJournalCaseAndExecution() {
    String caseId = investigated(paid("atomic", "1000").id());
    String id = (String) adjustments.propose(caseId, 2, "INCREASE", "10", "差异", proposer).get("id");
    adjustments.review(id, 1, true, "复核", reviewer);
    jdbc.update("UPDATE event_transport SET mode='CDC'");
    assertThatThrownBy(() -> adjustments.execute(id, 1, "执行", proposer))
        .isInstanceOf(IllegalStateException.class);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_journals WHERE source_type='ADJUSTMENT'", Long.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM finance_adjustments WHERE id=?", String.class, id))
        .isEqualTo("APPROVED");
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM finance_cases WHERE id=?", String.class, caseId))
        .isEqualTo("INVESTIGATED");
  }
}
