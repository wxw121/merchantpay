package dev.merchantpay.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.merchantpay.platform.sharding.ShardCatalog;
import dev.merchantpay.platform.web.*;
import java.time.Clock;
import java.util.Set;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;

class ShardApiTest extends FinancialFixture {
  MockMvc mvc;

  @BeforeEach
  void directoryApi() {
    ScriptUtils.executeSqlScript(
        schemaConnection, new ClassPathResource("db/migration/V24__shard_catalog.sql"));
    ScriptUtils.executeSqlScript(
        schemaConnection, new ClassPathResource("db/migration/V34__shard_quiescence.sql"));
    var catalog =
        new ShardCatalog(
            jdbc,
            new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())),
            Clock.systemUTC());
    mvc =
        MockMvcBuilders.standaloneSetup(new ShardController(new Access(), catalog))
            .setControllerAdvice(new ApiErrors(null))
            .build();
  }

  @Test
  void platformOperatorCanCreateAPlanWithoutChangingTheCurrentPhysicalPlacement() throws Exception {
    ScopedValue.where(Access.CURRENT, new Access.Caller("ops", null, Set.of("OPS")))
        .call(
            () -> {
              mvc.perform(get("/api/console/shards"))
                  .andExpect(status().isOk())
                  .andExpect(jsonPath("$.routes.length()").value(16))
                  .andExpect(jsonPath("$.executionEnabled").value(false));
              mvc.perform(
                      post("/api/console/shards/plans")
                          .contentType("application/json")
                          .content(
                              "{\"requestKey\":\"api-plan\",\"bucket\":2,\"targetShard\":3,\"expectedEpoch\":1,\"reason\":\"prepare\"}"))
                  .andExpect(status().isOk())
                  .andExpect(jsonPath("$.state").value("PLANNED"));
              mvc.perform(
                      get("/api/console/shards/resolve")
                          .param("kind", "PAYMENT")
                          .param("key", "p2_552ea710-7879-4b42-876a-f145cdcf51d3"))
                  .andExpect(status().isOk())
                  .andExpect(jsonPath("$.bucket").value(2))
                  .andExpect(jsonPath("$.physicalShard").value(0))
                  .andExpect(jsonPath("$.existenceVerified").value(false));
              return null;
            });
  }

  @Test
  void preparationRejectsCoercedOrOutOfRangeFieldsWithoutCreatingPlansOrAudit() throws Exception {
    ScopedValue.where(Access.CURRENT, new Access.Caller("ops", null, Set.of("OPS")))
        .call(
            () -> {
              for (String field : new String[] {"bucket", "targetShard", "expectedEpoch"}) {
                for (String value :
                    new String[] {"1.5", "\"1\"", "9223372036854775808", "null", "-1"}) {
                  String body =
                      "{\"requestKey\":\"invalid\",\"bucket\":2,\"targetShard\":3,\"expectedEpoch\":1,\"reason\":\"prepare\"}";
                  String original =
                      field.equals("bucket") ? "2" : field.equals("targetShard") ? "3" : "1";
                  body =
                      body.replace("\"" + field + "\":" + original, "\"" + field + "\":" + value);
                  mvc.perform(
                          post("/api/console/shards/plans")
                              .contentType("application/json")
                              .content(body))
                      .andExpect(status().isBadRequest());
                }
              }
              for (String body :
                  new String[] {
                    "[]",
                    "null",
                    "{}",
                    "{\"requestKey\":1,\"bucket\":2,\"targetShard\":3,\"expectedEpoch\":1,\"reason\":\"prepare\"}",
                    "{\"requestKey\":\"invalid\",\"bucket\":4294967298,\"targetShard\":3,\"expectedEpoch\":1,\"reason\":\"prepare\"}",
                    "{\"requestKey\":\"invalid\",\"bucket\":2,\"targetShard\":4294967299,\"expectedEpoch\":1,\"reason\":\"prepare\"}",
                    "{\"requestKey\":\"invalid\",\"bucket\":2,\"targetShard\":3,\"expectedEpoch\":0,\"reason\":\"prepare\"}",
                    "{\"requestKey\":\"invalid\",\"bucket\":2,\"targetShard\":3,\"expectedEpoch\":1,\"reason\":5}"
                  })
                mvc.perform(
                        post("/api/console/shards/plans")
                            .contentType("application/json")
                            .content(body))
                    .andExpect(status().isBadRequest());
              assertEquals(
                  0,
                  jdbc.queryForObject("SELECT COUNT(*) FROM v3_shard_migrations", Integer.class));
              assertEquals(
                  0,
                  jdbc.queryForObject(
                      "SELECT COUNT(*) FROM audit_records WHERE operation LIKE 'V3_SHARD_%'",
                      Integer.class));
              return null;
            });
  }

  @Test
  void cancellationRejectsCoercedVersionsBeforeChangingThePlan() throws Exception {
    ScopedValue.where(Access.CURRENT, new Access.Caller("ops", null, Set.of("OPS")))
        .call(
            () -> {
              mvc.perform(
                      post("/api/console/shards/plans")
                          .contentType("application/json")
                          .content(
                              "{\"requestKey\":\"valid\",\"bucket\":2,\"targetShard\":3,\"expectedEpoch\":1,\"reason\":\"prepare\"}"))
                  .andExpect(status().isOk());
              String id = jdbc.queryForObject("SELECT id FROM v3_shard_migrations", String.class);
              for (String value :
                  new String[] {"1.5", "\"1\"", "9223372036854775808", "null", "0", "-1"})
                mvc.perform(
                        post("/api/console/shards/plans/" + id + "/cancel")
                            .contentType("application/json")
                            .content("{\"expectedVersion\":" + value + ",\"reason\":\"cancel\"}"))
                    .andExpect(status().isBadRequest());
              assertEquals(
                  "PLANNED",
                  jdbc.queryForObject(
                      "SELECT state FROM v3_shard_migrations WHERE id=?", String.class, id));
              assertEquals(
                  1L,
                  jdbc.queryForObject(
                      "SELECT version FROM v3_shard_migrations WHERE id=?", Long.class, id));
              assertEquals(
                  0,
                  jdbc.queryForObject(
                      "SELECT COUNT(*) FROM audit_records WHERE operation='V3_SHARD_PLAN_CANCEL'",
                      Integer.class));
              return null;
            });
  }

  @Test
  void tenantBoundAdminAndMerchantCannotReadOrPlanThePlatformDirectory() throws Exception {
    for (var caller :
        new Access.Caller[] {
          new Access.Caller("merchant", "merchant-a", Set.of("MERCHANT")),
          new Access.Caller("scoped-admin", "merchant-a", Set.of("ADMIN"))
        })
      ScopedValue.where(Access.CURRENT, caller)
          .call(
              () -> {
                mvc.perform(get("/api/console/shards")).andExpect(status().isForbidden());
                mvc.perform(
                        post("/api/console/shards/plans")
                            .contentType("application/json")
                            .content(
                                "{\"requestKey\":\"forbidden\",\"bucket\":2,\"targetShard\":3,\"expectedEpoch\":1,\"reason\":\"prepare\"}"))
                    .andExpect(status().isForbidden());
                return null;
              });
  }
}
