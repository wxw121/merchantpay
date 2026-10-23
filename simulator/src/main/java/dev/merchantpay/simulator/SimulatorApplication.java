package dev.merchantpay.simulator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootApplication
@EnableScheduling
public class SimulatorApplication {
  public static void main(String[] args) {
    SpringApplication.run(SimulatorApplication.class, args);
  }

  @Bean
  TransactionTemplate transactions(PlatformTransactionManager manager) {
    return new TransactionTemplate(manager);
  }
}
