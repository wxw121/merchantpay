package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;

import dev.merchantpay.platform.reconciliation.ObjectStorage;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LegacyObjectStorageTest {
  @TempDir Path directory;
  final String legacy = "exports/11111111-1111-4111-8111-111111111111.zip";

  @Test
  void historicalLocalExportsRemainReadableThroughEveryReader() throws Exception {
    Path original = directory.resolve(legacy);
    Files.createDirectories(original.getParent());
    byte[] bytes = {1, 2, 3, 4};
    Files.write(original, bytes);
    try (var storage = new ObjectStorage(directory.toString())) {
      try (var stream = storage.open(legacy)) {
        assertThat(stream.readAllBytes()).isEqualTo(bytes);
      }
      var resource = storage.resource(legacy);
      try (var stream = resource.getInputStream()) {
        assertThat(resource.contentLength()).isEqualTo(bytes.length);
        assertThat(stream.readAllBytes()).isEqualTo(bytes);
      }
      assertThat(storage.localPath(legacy)).isEqualTo(original.toAbsolutePath());
    }
  }

  @Test
  void compatibilityDoesNotAllowLegacyPublicationS3OrPathTraversal() throws Exception {
    Path staged = Files.write(directory.resolve("staged"), new byte[] {1});
    try (var storage = new ObjectStorage(directory.toString())) {
      assertThatThrownBy(() -> storage.publish(staged, legacy))
          .isInstanceOf(IllegalStateException.class);
      for (String key : List.of("s3:" + legacy, "exports/../" + legacy, "../" + legacy)) {
        assertThatThrownBy(() -> storage.open(key)).isInstanceOf(IllegalStateException.class);
      }
      assertThat(Files.exists(staged)).isTrue();
    }
  }
}
