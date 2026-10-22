package vn.fashion.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
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

/** NOT-01 email 6: the reset link exists only in memory at send time; the secret comes from user-service. */
@ExtendWith(OutputCaptureExtension.class)
class ResetPasswordEmailIntegrationTest extends NotificationTestSupport {
  private static final String SECRET = "synthetic-reset-secret-AbC123_-xyz";
  private record Reply(int status, String body) { }
  private static final FakeSmtp smtp;
  private static final HttpServer user;
  private static final ECKey KEY;
  private static final AtomicReference<Reply> reply = new AtomicReference<>();
  private static final List<String> paths = new CopyOnWriteArrayList<>();
  private static final List<String> auths = new CopyOnWriteArrayList<>();
  static {
    try {
      smtp = new FakeSmtp();
      KEY = new ECKeyGenerator(Curve.P_256).generate();
      user = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
      user.createContext("/", ex -> {
        paths.add(ex.getRequestURI().getPath());
        auths.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
        Reply r = reply.get();
        byte[] b = r.body().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(r.status(), b.length);
        ex.getResponseBody().write(b);
        ex.close();
      });
      user.start();
    } catch (Exception e) { throw new ExceptionInInitializerError(e); }
  }

  @Autowired NotificationSender sender;
  @Autowired JavaMailSenderImpl mail;
  @Autowired MeterRegistry meters;

  @DynamicPropertySource static void props(DynamicPropertyRegistry registry) {
    registry.add("spring.mail.host", () -> "127.0.0.1");
    registry.add("spring.mail.port", () -> smtp.port());
    registry.add("fashion.notification.user-url", () -> "http://127.0.0.1:" + user.getAddress().getPort());
    registry.add("fashion.notification.service-private-key", () -> b64(privateKey()));
    registry.add("fashion.notification.service-public-key", () -> b64(publicKey()));
  }
  private static byte[] privateKey() { try { return KEY.toECPrivateKey().getEncoded(); } catch (Exception e) { throw new AssertionError(e); } }
  private static byte[] publicKey() { try { return KEY.toECPublicKey().getEncoded(); } catch (Exception e) { throw new AssertionError(e); } }
  private static String b64(byte[] k) { return Base64.getEncoder().encodeToString(k); }

  @AfterAll static void stop() throws IOException { smtp.close(); user.stop(0); }

  @BeforeEach void resetStubs() {
    smtp.mode = FakeSmtp.Mode.OK;
    smtp.messages.clear();
    mail.setPort(smtp.port());
    paths.clear();
    auths.clear();
    reply.set(found());
  }

  private static Reply found() {
    return new Reply(200, "{\"code\":\"OK\",\"data\":{\"challenge_id\":\"" + UUID.randomUUID() + "\",\"purpose\":\"RESET_PASSWORD\",\"secret\":\""
        + SECRET + "\",\"expires_at\":\"2030-01-01T00:00:00Z\"},\"metadata\":{\"request_id\":\"r\",\"trace_id\":\"t\"}}");
  }

  private UUID intake(Instant expiresAt) {
    UUID challenge = UUID.randomUUID();
    deliver(envelope(UUID.randomUUID(), "NOTIFY_RESET_PASSWORD", "{\"dedupe_key\":\"RESET_PASSWORD:" + challenge + "\",\"user_id\":\""
        + UUID.randomUUID() + "\",\"recipient\":{\"email\":\"to@example.invalid\"},\"channel\":\"EMAIL\",\"locale\":\"vi\","
        + "\"template_key\":\"RESET_PASSWORD\",\"challenge_id\":\"" + challenge + "\",\"expires_at\":\"" + expiresAt + "\"}"));
    return challenge;
  }
  private String status() { return jdbc.queryForObject("select status from notifications", String.class); }
  private String sentHtml() throws Exception {
    assertThat(smtp.messages).hasSize(1);
    var message = new MimeMessage(Session.getInstance(new java.util.Properties()),
        new ByteArrayInputStream(smtp.messages.get(0).getBytes(StandardCharsets.UTF_8)));
    return (String) message.getContent();
  }

  @Test void aResetEmailCarriesTheLinkOnlyAtSendTime() throws Exception {
    UUID challenge = intake(Instant.now().plusSeconds(1800));
    var row = jdbc.queryForMap("select rendered_body, expires_at from notifications");
    assertThat((String) row.get("rendered_body")).contains("@@RESET_URL@@").doesNotContain(SECRET);
    assertThat(row.get("expires_at")).isNotNull();
    assertThat(sender.runOnce()).isEqualTo(1);
    assertThat(status()).isEqualTo("SENT");
    assertThat(sentHtml()).contains("href=\"https://shop.example.invalid/reset-password#token=" + SECRET + "\"").doesNotContain("@@RESET_URL@@");
    assertThat(paths).containsExactly("/internal/api/v1/users/notification-secrets/" + challenge);
    assertThat(auths).allSatisfy(a -> assertThat(a).startsWith("Bearer "));
    assertThat(jdbc.queryForObject("select rendered_body from notifications", String.class)).doesNotContain(SECRET);
  }

  @Test void theLinkIsHtmlEscaped() throws Exception {
    reply.set(new Reply(200, found().body().replace(SECRET, "a&b<c")));
    intake(Instant.now().plusSeconds(1800));
    sender.runOnce();
    assertThat(sentHtml()).contains("#token=a&amp;b&lt;c\"").doesNotContain("a&b<c");
  }

  @Test void anExpiredResetIsNeverSent() {
    double before = meters.counter("notification.expired").count();
    intake(Instant.now().minusSeconds(5));
    sender.runOnce();
    assertThat(status()).isEqualTo("EXPIRED");
    assertThat(paths).isEmpty();
    assertThat(smtp.messages).isEmpty();

    reply.set(new Reply(404, "{\"code\":\"NOT_FOUND\",\"message\":\"SECRET_NOT_FOUND\",\"errors\":[],\"metadata\":{}}"));
    asOwner("delete from notifications");
    intake(Instant.now().plusSeconds(1800));
    sender.runOnce();
    assertThat(status()).isEqualTo("EXPIRED");
    assertThat(paths).hasSize(1);
    assertThat(smtp.messages).isEmpty();
    assertThat(meters.counter("notification.expired").count()).isEqualTo(before + 2);
  }

  @Test void aUserOutageRetries() {
    reply.set(new Reply(503, "{}"));
    intake(Instant.now().plusSeconds(1800));
    sender.runOnce();
    assertThat(jdbc.queryForMap("select status, retry_count from notifications")).containsEntry("status", "PENDING").containsEntry("retry_count", 1);
    assertThat(smtp.messages).isEmpty();
  }

  @Test void theResetTokenIsNeverStored(CapturedOutput output) {
    intake(Instant.now().plusSeconds(1800));
    sender.runOnce();
    assertThat(status()).isEqualTo("SENT");
    Map<String, Object> row = jdbc.queryForMap("select * from notifications");
    assertThat(row.values()).allSatisfy(v -> assertThat(String.valueOf(v)).doesNotContain(SECRET));
    assertThat(output.getAll()).doesNotContain(SECRET);
  }
}
