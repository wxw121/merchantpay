package dev.merchantpay.platform;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModuleArchitectureTest {
  @Test
  void applicationModulesHaveNoDependencyCycles() {
    ApplicationModules.of(PlatformApplication.class).verify();
  }
}
