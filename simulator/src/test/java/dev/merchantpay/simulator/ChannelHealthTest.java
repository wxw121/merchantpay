package dev.merchantpay.simulator;

import static org.assertj.core.api.Assertions.*;

import com.zaxxer.hikari.HikariDataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

class ChannelHealthTest {
  static String instant(String localTimestamp) {
    return java.sql.Timestamp.valueOf(localTimestamp).toInstant().toString();
  }

  record Query(String sql, Object[] args) {}

  static class RecordingJdbc extends JdbcTemplate {
    final List<Query> queries = new ArrayList<>();

    RecordingJdbc(HikariDataSource source) {
      super(source);
    }

    @Override
    public <T> T queryForObject(String sql, Class<T> type, Object... args) {
      queries.add(new Query(sql, args));
      return super.queryForObject(sql, type, args);
    }

    @Override
    public <T> List<T> query(String sql, RowMapper<T> mapper) {
      queries.add(new Query(sql, new Object[0]));
      return super.query(sql, mapper);
    }
  }

  @Test
  void historyDoesNotExpandHealthAndCallbackScansAndMerchantCountsStayExact() {
    try (var source = new HikariDataSource()) {
      source.setJdbcUrl("jdbc:h2:mem:health_" + UUID.randomUUID() + ";MODE=MySQL");
      source.setMaximumPoolSize(2);
      new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
      var jdbc = new JdbcTemplate(source);
      jdbc.update(
          "INSERT INTO receipts(channel,operation,aggregate_id,payment_id,merchant_id,business_no,request_hash,amount_minor,currency,status,reference,fee_minor,notified,created_at) SELECT 'MOCK_A','PAYMENT','history-' || X,'history-' || X,'merchant-a','old','hash',100,'CNY','SUCCEEDED','ref-' || X,1,TRUE,TIMESTAMP '2026-10-17 00:00:00' FROM SYSTEM_RANGE(1,20000)");
      jdbc.update(
          "INSERT INTO receipts(channel,operation,aggregate_id,payment_id,merchant_id,business_no,request_hash,amount_minor,currency,status,reference,fee_minor,notified,created_at) VALUES ('MOCK_A','PAYMENT','pending','pending','merchant-a','pending:1','h',100,'CNY','PENDING','pending-ref',0,FALSE,TIMESTAMP '2026-10-17 00:00:00'),('MOCK_A','PAYMENT','callback-a','callback-a','merchant-a','a','h',100,'CNY','SUCCEEDED','a-ref',1,FALSE,TIMESTAMP '2026-10-18 01:00:00'),('MOCK_A','REFUND','callback-b','history-1','merchant-b','b','h',10,'CNY','FAILED','b-ref',0,FALSE,TIMESTAMP '2026-10-18 02:00:00'),('MOCK_B','PAYMENT','callback-c','callback-c','merchant-a','c','h',100,'CNY','CLOSED','c-ref',0,FALSE,TIMESTAMP '2026-10-18 03:00:00')");
      jdbc.execute("ANALYZE");
      var recording = new RecordingJdbc(source);
      var store =
          new ChannelStore(
              recording, new TransactionTemplate(new DataSourceTransactionManager(source)));
      assertThat(store.health("MOCK_A", null))
          .isEqualTo(new ChannelStore.Health("MOCK_A", 2, 1, instant("2026-10-18 01:00:00")));
      assertThat(store.health("MOCK_A", "merchant-a"))
          .isEqualTo(new ChannelStore.Health("MOCK_A", 1, 1, instant("2026-10-18 01:00:00")));
      assertThat(store.health("MOCK_A", "merchant-b"))
          .isEqualTo(new ChannelStore.Health("MOCK_A", 1, 0, instant("2026-10-18 02:00:00")));
      assertThat(store.health("MOCK_B", null))
          .isEqualTo(new ChannelStore.Health("MOCK_B", 1, 0, instant("2026-10-18 03:00:00")));
      assertThat(store.health("MOCK_B", "absent"))
          .isEqualTo(new ChannelStore.Health("MOCK_B", 0, 0, ""));
      assertThat(store.pendingNotifications())
          .extracting(ChannelStore.Receipt::aggregateId)
          .containsExactly("callback-a", "callback-b", "callback-c");
      var scanPattern = Pattern.compile("scanCount: (\\d+)");
      for (var query : recording.queries) {
        String plan =
            jdbc.queryForObject("EXPLAIN ANALYZE " + query.sql(), String.class, query.args());
        var matches = scanPattern.matcher(plan);
        long scans = 0;
        boolean measured = false;
        while (matches.find()) {
          measured = true;
          scans += Long.parseLong(matches.group(1));
        }
        assertThat(measured).as(plan).isTrue();
        assertThat(scans).as(plan).isLessThanOrEqualTo(100);
      }
      store.notified(store.query("MOCK_A", "PAYMENT", "callback-a"));
      store.close("MOCK_A", "pending");
      assertThat(store.health("MOCK_A", "merchant-a").pendingCallbacks()).isEqualTo(1);
      assertThat(store.health("MOCK_A", "merchant-a").pendingPayments()).isZero();
      assertThat(store.health("MOCK_A", "merchant-a").oldestCallbackAt())
          .isEqualTo(instant("2026-10-17 00:00:00"));
    }
  }
}
