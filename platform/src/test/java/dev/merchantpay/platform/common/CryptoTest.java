package dev.merchantpay.platform.common;

import static org.assertj.core.api.Assertions.*;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class CryptoTest {
  @Test
  void encryptedKeysUseRandomNoncesAndRejectTampering() {
    var vault = new KeyVault(Base64.getEncoder().encodeToString(new byte[32]));
    var first = vault.encrypt("merchant-secret");
    var second = vault.encrypt("merchant-secret");
    assertThat(first).isNotEqualTo(second).doesNotContain("merchant-secret");
    assertThat(vault.decrypt(first)).isEqualTo("merchant-secret");
    var bytes = Base64.getDecoder().decode(first);
    bytes[15] ^= 1;
    assertThatThrownBy(() -> vault.decrypt(Base64.getEncoder().encodeToString(bytes)))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void signatureBindsMethodPathQueryTimestampNonceAndBody() {
    var signature =
        Signatures.sign(
            "secret", "1000", "nonce", "POST", "/api/v1/payments?x=1", "{\"amountMinor\":\"100\"}");
    assertThat(
            Signatures.verify(
                "secret",
                signature,
                "1000",
                "nonce",
                "POST",
                "/api/v1/payments?x=1",
                "{\"amountMinor\":\"100\"}"))
        .isTrue();
    assertThat(
            Signatures.verify(
                "secret",
                signature,
                "1000",
                "nonce",
                "POST",
                "/api/v1/payments?x=2",
                "{\"amountMinor\":\"100\"}"))
        .isFalse();
    assertThat(
            Signatures.verify(
                "secret",
                signature,
                "1000",
                "nonce",
                "POST",
                "/api/v1/payments?x=1",
                "{\"amountMinor\":\"101\"}"))
        .isFalse();
  }

  @Test
  void webhookUrlsHaveStrictProtocolAndAuthorityBoundaries() {
    var policy = new NotificationPolicy("");
    assertThat(policy.validate("https://merchant.example/payments"))
        .isEqualTo("https://merchant.example/payments");
    for (var url :
        new String[] {
          "http://127.0.0.1/",
          "file:///etc/passwd",
          "https://user:password@example.org/",
          "https://example.org/#fragment",
          "https://example.org:8080/",
          "https://127.0.0.1/",
          "https://[::1]/"
        }) {
      assertThatThrownBy(() -> policy.validate(url)).isInstanceOf(ApiException.class);
    }
  }
}
