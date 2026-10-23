package dev.merchantpay.platform.common;

public final class Signatures {
  private Signatures() {}

  public static String sign(
      String secret, String timestamp, String nonce, String method, String path, String body) {
    return hmac(
        secret, timestamp + "\n" + nonce + "\n" + method + "\n" + path + "\n" + sha256(body));
  }

  public static String sha256(String text) {
    try {
      return java.util.HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  public static String hmac(String secret, String content) {
    try {
      var mac = javax.crypto.Mac.getInstance("HmacSHA256");
      mac.init(
          new javax.crypto.spec.SecretKeySpec(
              secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
      return java.util.HexFormat.of()
          .formatHex(mac.doFinal(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.GeneralSecurityException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  public static boolean verify(
      String secret,
      String signature,
      String timestamp,
      String nonce,
      String method,
      String path,
      String body) {
    return constantEquals(sign(secret, timestamp, nonce, method, path, body), signature);
  }

  public static boolean constantEquals(String expected, String actual) {
    if (actual == null || actual.length() != expected.length()) {
      return false;
    }
    return java.security.MessageDigest.isEqual(
        expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
        actual.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }
}
