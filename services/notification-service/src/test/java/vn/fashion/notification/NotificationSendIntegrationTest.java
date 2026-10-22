package vn.fashion.notification;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@ExtendWith(OutputCaptureExtension.class)
class NotificationSendIntegrationTest extends NotificationTestSupport {
  private static final FakeSmtp smtp;
  static {
    try { smtp = new FakeSmtp(); } catch (IOException e) { throw new ExceptionInInitializerError(e); }
  }
  private static final String RECIPIENT = "to@example.invalid";

  @Autowired NotificationSender sender;
  @Autowired JavaMailSenderImpl mail;
  @Autowired MeterRegistry meters;

  @DynamicPropertySource static void mailProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.mail.host", () -> "127.0.0.1");
    registry.add("spring.mail.port", () -> smtp.port());
    registry.add("spring.mail.properties.mail.smtp.timeout", () -> 1000);
    registry.add("spring.mail.properties.mail.smtp.writetimeout", () -> 1000);
  }

  @AfterAll static void stop() throws IOException { smtp.close(); }

  @BeforeEach void resetSmtp() {
    smtp.mode = FakeSmtp.Mode.OK;
    smtp.messages.clear();
    mail.setPort(smtp.port());
  }

  private UUID seed(String status) {
    UUID id = UUID.randomUUID();
    String lease = status.equals("SENDING") ? "gen_random_uuid(), now() - interval '5 seconds'" : "null, null";
    jdbc.update("insert into notifications (id, event_id, dedupe_key, recipient, channel, template_id, template_version, rendered_subject, "
        + "rendered_body, locale, status, lease_token, lease_until) select ?, gen_random_uuid(), ?, ?, 'EMAIL', id, 1, 'Secret subject', "
        + "'<p>secret body</p>', 'vi', ?, " + lease + " from notification_templates where template_key = 'ORDER_CONFIRMED' and locale = 'vi'",
        id, "order:" + id + ":confirmed", RECIPIENT, status);
    return id;
  }
  private UUID seed() { return seed("PENDING"); }
  private String status(UUID id) { return jdbc.queryForObject("select status from notifications where id = ?", String.class, id); }
  private double counter(String name, String... tags) { return meters.counter(name, tags).count(); }
  private void dueNow(UUID id) { jdbc.update("update notifications set next_retry_at = now() where id = ?", id); }

  @Test void aPendingEmailIsSentOnce() {
    UUID id = seed();
    double before = counter("notification.sent");
    assertThat(sender.runOnce()).isEqualTo(1);
    var row = jdbc.queryForMap("select * from notifications where id = ?", id);
    assertThat(row).containsEntry("status", "SENT").containsEntry("lease_token", null);
    assertThat(row.get("sent_at")).isNotNull();
    assertThat(smtp.messages).hasSize(1);
    assertThat(smtp.messages.get(0)).contains("Message-ID: <" + id + "@example.invalid>");
    assertThat(counter("notification.sent")).isEqualTo(before + 1);
    assertThat(sender.runOnce()).isZero();
    assertThat(smtp.messages).hasSize(1);
  }

  @Test void aTemporaryRefusalRetriesThenFails() {
    smtp.mode = FakeSmtp.Mode.TEMP_AT_MAIL;
    UUID id = seed();
    double before = counter("notification.failed", "template", "ORDER_CONFIRMED");
    for (int failure = 1; failure <= 5; failure++) {
      sender.runOnce();
      var row = jdbc.queryForMap("select status, retry_count, extract(epoch from next_retry_at - now()) as wait from notifications where id = ?", id);
      double minutes = 1 << (failure - 1);
      assertThat(row).containsEntry("status", "PENDING").containsEntry("retry_count", failure);
      assertThat(((Number) row.get("wait")).doubleValue() / 60).isBetween(minutes * 0.8 - 0.05, minutes * 1.2 + 0.01);
      dueNow(id);
    }
    sender.runOnce();
    assertThat(status(id)).isEqualTo("FAILED");
    assertThat(counter("notification.failed", "template", "ORDER_CONFIRMED")).isEqualTo(before + 1);
    assertThat(smtp.messages).isEmpty();
  }

  @Test void aClosedPortRetries() throws IOException {
    mail.setPort(FakeSmtp.closedPort());
    UUID id = seed();
    sender.runOnce();
    assertThat(jdbc.queryForMap("select status, retry_count from notifications where id = ?", id))
        .containsEntry("status", "PENDING").containsEntry("retry_count", 1);
  }

  @Test void aPermanentRefusalFailsAtOnce() {
    smtp.mode = FakeSmtp.Mode.PERM_AT_RCPT;
    UUID id = seed();
    sender.runOnce();
    assertThat(jdbc.queryForMap("select status, retry_count from notifications where id = ?", id))
        .containsEntry("status", "FAILED").containsEntry("retry_count", 0);
    assertThat(sender.runOnce()).isZero();
  }

  @Test void silenceAfterDataIsUnknown() {
    smtp.mode = FakeSmtp.Mode.SILENT_AFTER_DATA;
    UUID id = seed();
    double before = counter("notification.unknown");
    sender.runOnce();
    assertThat(jdbc.queryForMap("select status, retry_count, lease_token from notifications where id = ?", id))
        .containsEntry("status", "UNKNOWN").containsEntry("retry_count", 0).containsEntry("lease_token", null);
    assertThat(counter("notification.unknown")).isEqualTo(before + 1);
    smtp.mode = FakeSmtp.Mode.OK;
    assertThat(sender.runOnce()).isZero();
    assertThat(smtp.messages).isEmpty();
  }

  @Test void aSendingRowWithAnExpiredLeaseBecomesUnknown() {
    UUID id = seed("SENDING");
    sender.runOnce();
    assertThat(status(id)).isEqualTo("UNKNOWN");
    assertThat(smtp.messages).isEmpty();
  }

  @Test void twoWorkersSendEachEmailOnce() {
    List<UUID> ids = java.util.stream.IntStream.range(0, 10).mapToObj(i -> seed()).toList();
    CompletableFuture.allOf(CompletableFuture.runAsync(sender::runOnce), CompletableFuture.runAsync(sender::runOnce))
        .orTimeout(30, TimeUnit.SECONDS).join();
    assertThat(smtp.messages).hasSize(10);
    assertThat(count("select count(*) from notifications where status = 'SENT'")).isEqualTo(10);
    assertThat(ids).allSatisfy(id -> assertThat(smtp.messages.stream().filter(m -> m.contains("<" + id + "@")).count()).isEqualTo(1));
  }

  @Test void logsCarryNoRecipientOrBody(CapturedOutput output) {
    UUID id = seed();
    smtp.mode = FakeSmtp.Mode.PERM_AT_RCPT;
    sender.runOnce();
    seed("SENDING");
    sender.runOnce();
    assertThat(output.getAll()).contains(id.toString()).doesNotContain(RECIPIENT).doesNotContain("Secret subject").doesNotContain("secret body");
  }
}
