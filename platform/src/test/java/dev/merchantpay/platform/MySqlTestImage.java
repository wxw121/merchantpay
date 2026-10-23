package dev.merchantpay.platform;

import org.testcontainers.utility.DockerImageName;

/** The same non-root maintenance image used by the local deployment. Build it before tests. */
final class MySqlTestImage {
  static final DockerImageName IMAGE =
      DockerImageName.parse("merchantpay/mysql:v1-local").asCompatibleSubstituteFor("mysql");
}
