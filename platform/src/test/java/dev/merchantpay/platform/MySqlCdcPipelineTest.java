package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import dev.merchantpay.platform.outbox.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.kafka.listener.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.*;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real binlog snapshot/stream -> SMT -> broker -> production listener/DB receipts, with restart.
 */
class MySqlCdcPipelineTest extends FinancialFixture {
  static final Network NETWORK = Network.newNetwork();
  static final MySQLContainer MYSQL =
      new MySQLContainer(MySqlTestImage.IMAGE)
          .withDatabaseName("merchantpay")
          .withUsername("test")
          .withPassword("test")
          .withNetwork(NETWORK)
          .withNetworkAliases("mysql")
          .withCommand(
              "--server-id=184001",
              "--log-bin=mysql-bin",
              "--binlog-format=ROW",
              "--binlog-row-image=FULL",
              "--innodb-buffer-pool-size=134217728")
          .withCreateContainerCmdModifier(
              c -> c.getHostConfig().withNanoCPUs(1000000000L).withMemory(805306368L));
  static final KafkaContainer KAFKA =
      new KafkaContainer("apache/kafka:4.3.2")
          .withNetwork(NETWORK)
          .withListener("kafka:19093")
          .withEnv("KAFKA_HEAP_OPTS", "-Xms256m -Xmx512m -XX:ActiveProcessorCount=1")
          .withCreateContainerCmdModifier(
              c -> c.getHostConfig().withNanoCPUs(1000000000L).withMemory(805306368L));
  static final GenericContainer<?> CONNECT =
      new GenericContainer<>("quay.io/debezium/connect:3.7.0.Final")
          .withNetwork(NETWORK)
          .withExposedPorts(8083)
          .withEnv("BOOTSTRAP_SERVERS", "kafka:19093")
          .withEnv("GROUP_ID", "v2-fixture")
          .withEnv("CONFIG_STORAGE_TOPIC", "fixture-config")
          .withEnv("OFFSET_STORAGE_TOPIC", "fixture-offset")
          .withEnv("STATUS_STORAGE_TOPIC", "fixture-status")
          .withEnv("CONNECT_CONFIG_STORAGE_REPLICATION_FACTOR", "1")
          .withEnv("CONNECT_OFFSET_STORAGE_REPLICATION_FACTOR", "1")
          .withEnv("CONNECT_STATUS_STORAGE_REPLICATION_FACTOR", "1")
          .withEnv("CONNECT_CONFIG_PROVIDERS", "env")
          .withEnv(
              "CONNECT_CONFIG_PROVIDERS_ENV_CLASS",
              "org.apache.kafka.common.config.provider.EnvVarConfigProvider")
          .withEnv("V2_CDC_USER", "cdc")
          .withEnv("V2_CDC_PASSWORD", "test")
          .withEnv("HEAP_OPTS", "-Xms256m -Xmx640m -XX:ActiveProcessorCount=1")
          .waitingFor(Wait.forHttp("/connectors").forPort(8083))
          .withStartupTimeout(Duration.ofMinutes(2))
          .withCreateContainerCmdModifier(
              c -> c.getHostConfig().withNanoCPUs(1000000000L).withMemory(1073741824L));

  @BeforeAll
  static void start() {
    MYSQL.start();
    KAFKA.start();
    CONNECT.start();
  }

  @AfterAll
  static void stop() {
    CONNECT.stop();
    KAFKA.stop();
    MYSQL.stop();
    NETWORK.close();
  }

  @Override
  DriverManagerDataSource source() {
    return MySqlFinanceDatabase.clean(MYSQL);
  }

  @Test
  void snapshotOverlapStreamRollbackPoisonAndConsumerRestartStayExactlyOnce() throws Exception {
    ScriptUtils.executeSqlScript(
        schemaConnection, new ClassPathResource("db/migration/V18__reliable_domain_events.sql"));
    var tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    var json = JsonMapper.builder().build();
    var clock = Clock.systemUTC();
    var polling =
        new OutboxService(jdbc, clock, false, new EventJournal(jdbc, clock, json, "POLLING"));
    tx.executeWithoutResult(t -> polling.enqueue("old-polling", "EXPORT", "old", "merchant-a"));
    jdbc.update("UPDATE outbox_tasks SET status='DONE'");
    try (var c = jdbc.getDataSource().getConnection()) {
      EventTransportMaintenance.switchMode(c, "POLLING", "CDC", true);
    }
    var cdc = new OutboxService(jdbc, clock, false, new EventJournal(jdbc, clock, json, "CDC"));
    tx.executeWithoutResult(t -> cdc.enqueue("snapshot-cdc", "EXPORT", "snapshot", "merchant-a"));
    var grant =
        MYSQL.execInContainer(
            "mysql",
            "-uroot",
            "-ptest",
            "-e",
            "CREATE USER 'cdc'@'%' IDENTIFIED BY 'test'; GRANT SELECT,LOCK TABLES ON merchantpay.* TO 'cdc'@'%'; GRANT RELOAD,SHOW DATABASES,REPLICATION SLAVE,REPLICATION CLIENT ON *.* TO 'cdc'@'%';");
    assertThat(grant.getExitCode()).as(grant.getStderr()).isZero();
    try (var admin = Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
      admin
          .createTopics(List.of(new NewTopic("outbox.event.merchantpay", 3, (short) 1)))
          .all()
          .get(15, TimeUnit.SECONDS);
    }
    Path config = Path.of("infra/connect/outbox.json");
    if (!Files.exists(config)) config = Path.of("../infra/connect/outbox.json");
    var node = (tools.jackson.databind.node.ObjectNode) json.readTree(Files.readString(config));
    node.put("schema.history.internal.kafka.bootstrap.servers", "kafka:19093");
    String base = "http://" + CONNECT.getHost() + ":" + CONNECT.getMappedPort(8083);
    var response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create(base + "/connectors/fixture/config"))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(node)))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    var delivery = new EventDelivery(jdbc, tx, clock, json);
    var factory =
        new EventKafkaConfiguration().eventListenerFactory(KAFKA.getBootstrapServers(), delivery);
    var listener = new EventKafkaListener(delivery);
    var container = factory.createContainer("outbox.event.merchantpay");
    container.getContainerProperties().setGroupId(EventDelivery.CONSUMER);
    container.setupMessageListener(
        (AcknowledgingMessageListener<String, String>) listener::onEvent);
    try {
      container.start();
      await()
          .atMost(Duration.ofSeconds(90))
          .untilAsserted(() -> assertThat(count("event_receipts")).isEqualTo(2));
      assertThat(count("outbox_tasks"))
          .isEqualTo(2); // polling snapshot did not recreate an old job
      container.stop();
      tx.executeWithoutResult(t -> cdc.enqueue("stream-cdc", "EXPORT", "stream", "merchant-a"));
      assertThatThrownBy(
              () ->
                  tx.executeWithoutResult(
                      t -> {
                        cdc.enqueue("rolled-back", "EXPORT", "rollback", "merchant-a");
                        throw new IllegalStateException("rollback");
                      }))
          .isInstanceOf(IllegalStateException.class);
      container.start();
      await()
          .atMost(Duration.ofSeconds(45))
          .untilAsserted(() -> assertThat(count("event_receipts")).isEqualTo(3));
      try (var producer =
          new KafkaProducer<String, String>(
              Map.of(
                  "bootstrap.servers",
                  KAFKA.getBootstrapServers(),
                  "key.serializer",
                  StringSerializer.class,
                  "value.serializer",
                  StringSerializer.class))) {
        String payload =
            jdbc.queryForObject(
                "SELECT payload FROM domain_events WHERE event_id='stream-cdc'", String.class);
        producer
            .send(new ProducerRecord<>("outbox.event.merchantpay", "stream", payload))
            .get(15, TimeUnit.SECONDS);
        producer
            .send(new ProducerRecord<>("outbox.event.merchantpay", "stream", "invalid-json"))
            .get(15, TimeUnit.SECONDS);
        producer
            .send(
                new ProducerRecord<>(
                    "outbox.event.merchantpay",
                    "stream",
                    payload.replace("merchant-a", "merchant-b")))
            .get(15, TimeUnit.SECONDS);
      }
      await()
          .atMost(Duration.ofSeconds(30))
          .untilAsserted(() -> assertThat(count("event_quarantine")).isEqualTo(2));
      assertThat(count("event_receipts")).isEqualTo(3);
      assertThat(count("outbox_tasks")).isEqualTo(3);
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM outbox_events WHERE event_id='rolled-back'", Long.class))
          .isZero();
    } finally {
      container.stop();
    }
  }
}
