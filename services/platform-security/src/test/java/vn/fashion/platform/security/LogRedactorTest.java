package vn.fashion.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LogRedactorTest {
  @Test
  void masksEmailAddresses() {
    assertThat(LogRedactor.redact("contact person.name+tag@example.test now"))
        .isEqualTo("contact [email] now");
  }

  @Test
  void masksBearerTokensAndJwts() {
    assertThat(LogRedactor.redact("Authorization: Bearer abc.DEF-123_x"))
        .isEqualTo("Authorization: Bearer [redacted]");
    assertThat(LogRedactor.redact("token eyJhbGciOiJFUzI1NiJ9.eyJpc3MiOiJvcmRlciJ9.c2ln end"))
        .isEqualTo("token [jwt] end");
  }

  @Test
  void masksVietnamesePhoneNumbers() {
    assertThat(LogRedactor.redact("call 0912345678 or +84912345678"))
        .isEqualTo("call [phone] or [phone]");
  }

  @Test
  void masksSecretKeyValues() {
    assertThat(LogRedactor.redact("login password=hunter2 otp: 123456 ok"))
        .isEqualTo("login password=[redacted] otp=[redacted] ok");
  }

  @Test
  void leavesOrdinaryTextIdsAndAmountsAlone() {
    String text = "order FS-SYNTH-0001 total 398000 VND sku SYNTH-TEE-M-BLK id 55555555-5555-4555-8555-555555555555";
    assertThat(LogRedactor.redact(text)).isEqualTo(text);
  }

  @Test
  void toleratesNull() {
    assertThat(LogRedactor.redact(null)).isNull();
  }
}
