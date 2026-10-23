package dev.merchantpay.platform.common;

public final class Csv {
  private Csv() {}

  public static String cell(String value) {
    if (value == null) {
      return "\"\"";
    }
    String stripped = value.stripLeading();
    if (!stripped.isEmpty() && "=+-@".indexOf(stripped.charAt(0)) >= 0
        || value.startsWith("\t")
        || value.startsWith("\r")) {
      value = "'" + value;
    }
    return "\"" + value.replace("\"", "\"\"") + "\"";
  }
}
