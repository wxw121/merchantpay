package dev.merchantpay.platform.common;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CsvTest {
  @Test
  void exportPreservesTextAndNeutralizesSpreadsheetFormulas() {
    assertThat(Csv.cell("=1+1")).isEqualTo("\"'=1+1\"");
    assertThat(Csv.cell("@command")).isEqualTo("\"'@command\"");
    assertThat(Csv.cell("order,\"1\"\nnext")).isEqualTo("\"order,\"\"1\"\"\nnext\"");
    assertThat(Csv.cell("9007199254740993")).isEqualTo("\"9007199254740993\"");
  }
}
