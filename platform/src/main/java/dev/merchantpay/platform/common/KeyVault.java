package dev.merchantpay.platform.common;

@org.springframework.stereotype.Component
public class KeyVault {
  private final javax.crypto.spec.SecretKeySpec key;
  private final java.security.SecureRandom random = new java.security.SecureRandom();

  public KeyVault(
      @org.springframework.beans.factory.annotation.Value("${merchantpay.master-key}") String key) {
    byte[] bytes = java.util.Base64.getDecoder().decode(key);
    if (bytes.length != 32) {
      throw new IllegalArgumentException("Master key must contain 32 bytes");
    }
    this.key = new javax.crypto.spec.SecretKeySpec(bytes, "AES");
  }

  public String encrypt(String value) {
    try {
      byte[] nonce = new byte[12];
      random.nextBytes(nonce);
      var cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          javax.crypto.Cipher.ENCRYPT_MODE,
          key,
          new javax.crypto.spec.GCMParameterSpec(128, nonce));
      cipher.updateAAD("merchantpay-key-v1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
      byte[] encrypted = cipher.doFinal(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return java.util.Base64.getEncoder()
          .encodeToString(
              java.nio.ByteBuffer.allocate(12 + encrypted.length)
                  .put(nonce)
                  .put(encrypted)
                  .array());
    } catch (java.security.GeneralSecurityException failure) {
      throw new IllegalStateException("Key encryption failed", failure);
    }
  }

  public String decrypt(String value) {
    try {
      byte[] bytes = java.util.Base64.getDecoder().decode(value);
      if (bytes.length < 29) {
        throw new IllegalArgumentException("Invalid encrypted key");
      }
      var cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          javax.crypto.Cipher.DECRYPT_MODE,
          key,
          new javax.crypto.spec.GCMParameterSpec(128, bytes, 0, 12));
      cipher.updateAAD("merchantpay-key-v1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return new String(
          cipher.doFinal(bytes, 12, bytes.length - 12), java.nio.charset.StandardCharsets.UTF_8);
    } catch (java.security.GeneralSecurityException failure) {
      throw new IllegalStateException("Encrypted key authentication failed", failure);
    }
  }
}
