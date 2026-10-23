package dev.merchantpay.platform.orchestration;

import dev.merchantpay.platform.merchant.MerchantService;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
@Profile("local")
public class LocalDemoData implements ApplicationRunner {
  private final MerchantService merchants;
  private final JdbcTemplate jdbc;
  private final JsonMapper json;
  private final Path directory;
  private final String localWebhook;

  public LocalDemoData(
      MerchantService merchants,
      JdbcTemplate jdbc,
      JsonMapper json,
      @Value("${merchantpay.data-directory}") String directory,
      @Value("${merchantpay.local-webhook:}") String localWebhook) {
    this.merchants = merchants;
    this.jdbc = jdbc;
    this.json = json;
    this.directory = Path.of(directory);
    this.localWebhook = localWebhook;
  }

  @Override
  public void run(ApplicationArguments arguments) throws Exception {
    var keys = new ArrayList<MerchantService.Key>();
    for (String id : List.of("merchant-a", "merchant-b")) {
      if (jdbc.queryForList("SELECT id FROM merchants WHERE id=?", id).isEmpty()) {
        merchants.configure(
            id,
            id.equals("merchant-a") ? "演示商户 A" : "演示商户 B",
            true,
            localWebhook,
            "local-bootstrap");
        keys.add(merchants.rotate(id, "local-bootstrap"));
      }
    }
    if (!keys.isEmpty()) {
      Files.createDirectories(directory);
      json.writeValue(directory.resolve("local-api-credentials.json").toFile(), keys);
    }
  }
}
