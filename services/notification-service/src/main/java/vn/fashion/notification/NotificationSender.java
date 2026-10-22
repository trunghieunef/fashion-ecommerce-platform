package vn.fashion.notification;

import io.micrometer.core.instrument.MeterRegistry;
import org.eclipse.angus.mail.util.MailConnectException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.springframework.web.util.HtmlUtils;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.eclipse.angus.mail.smtp.SMTPSenderFailedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sends PENDING emails: one-row claim with a 60 s lease (no transaction held during SMTP), result written only while
 * the lease is still ours. SENDING rows whose lease ran out are UNKNOWN: SMTP has no lookup, so never resend blindly.
 */
@Component
public class NotificationSender {
  private static final Logger log = LoggerFactory.getLogger(NotificationSender.class);
  private static final int BATCH = 20;
  private static final int MAX_RETRIES = 5;

  private record Claimed(UUID id, UUID leaseToken, String dedupeKey, String template, String recipient, String subject, String body, int retryCount, java.time.Instant expiresAt) { }
  private enum Failure { RETRY, PERMANENT, UNKNOWN }

  private final JdbcClient jdbc;
  private final JavaMailSenderImpl mail;
  private final MeterRegistry meters;
  private final String from;
  private final String messageIdDomain;
  private final boolean workerEnabled;
  private final ResetPasswordSecrets secrets;
  private final String storefront;

  public NotificationSender(JdbcClient jdbc, JavaMailSenderImpl mail, MeterRegistry meters, ResetPasswordSecrets secrets,
                            @Value("${fashion.notification.storefront-base-url}") String storefront,
                            @Value("${fashion.notification.mail-from}") String from,
                            @Value("${fashion.notification.message-id-domain}") String messageIdDomain,
                            @Value("${fashion.notification.worker.enabled:true}") boolean workerEnabled) {
    this.jdbc = jdbc;
    this.mail = mail;
    this.meters = meters;
    this.from = from;
    this.messageIdDomain = messageIdDomain;
    this.workerEnabled = workerEnabled;
    this.secrets = secrets;
    this.storefront = storefront.endsWith("/") ? storefront.substring(0, storefront.length() - 1) : storefront;
  }

  @Scheduled(fixedDelay = 2000)
  void tick() {
    if (!workerEnabled) return;
    try { runOnce(); } catch (RuntimeException e) { log.warn("notification sender tick failed: {}", e.getClass().getSimpleName()); }
  }

  /** Marks expired SENDING rows UNKNOWN, then sends up to a batch of due rows; returns how many rows it handled. */
  public int runOnce() {
    int handled = sweepExpiredLeases();
    for (int i = 0; i < BATCH; i++) {
      // One claim per row so a slow send cannot eat the lease of the rows claimed after it.
      Optional<Claimed> next = claim();
      if (next.isEmpty()) break;
      handle(next.get());
      handled++;
    }
    return handled;
  }

  private int sweepExpiredLeases() {
    var rows = jdbc.sql("""
        update notifications set status = 'UNKNOWN', lease_token = null, lease_until = null, last_error = 'lease expired while sending'
        where status = 'SENDING' and lease_until < now()
        returning id, dedupe_key, (select template_key from notification_templates t where t.id = template_id) as template
        """).query((rs, n) -> new String[] {rs.getString("id"), rs.getString("dedupe_key"), rs.getString("template")}).list();
    for (String[] r : rows) {
      meters.counter("notification.unknown").increment();
      log.warn("notification_id={} dedupe_key={} template={} status=UNKNOWN", r[0], r[1], r[2]);
    }
    return rows.size();
  }

  private Optional<Claimed> claim() {
    return jdbc.sql("""
        update notifications set status = 'SENDING', lease_token = gen_random_uuid(), lease_until = now() + interval '60 seconds'
        where id = (select id from notifications where status = 'PENDING' and next_retry_at <= now()
                    order by next_retry_at limit 1 for update skip locked)
        returning id, lease_token, dedupe_key, recipient, rendered_subject, rendered_body, retry_count, expires_at,
                  (select template_key from notification_templates t where t.id = template_id) as template
        """).query((rs, n) -> new Claimed(rs.getObject("id", UUID.class), rs.getObject("lease_token", UUID.class), rs.getString("dedupe_key"),
        rs.getString("template"), rs.getString("recipient"), rs.getString("rendered_subject"), rs.getString("rendered_body"),
        rs.getInt("retry_count"), rs.getTimestamp("expires_at") == null ? null : rs.getTimestamp("expires_at").toInstant())).optional();
  }

  private void handle(Claimed c) {
    String body = c.body();
    if (c.template().equals("RESET_PASSWORD")) {
      // The link is built here, in memory only; the HTTP call to user-service holds no transaction or row lock.
      try {
        Optional<String> secret = c.expiresAt() != null && !c.expiresAt().isAfter(Instant.now()) ? Optional.empty()
            : secrets.fetch(UUID.fromString(c.dedupeKey().substring("RESET_PASSWORD:".length())));
        if (secret.isEmpty()) {
          if (finish(c, "status = 'EXPIRED', last_error = null", "EXPIRED")) meters.counter("notification.expired").increment();
          return;
        }
        body = body.replace("@@RESET_URL@@", HtmlUtils.htmlEscape(storefront + "/reset-password#token=" + secret.get()));
      } catch (RuntimeException e) {
        fail(c, Failure.RETRY, e); // user-service down or slow: nothing was sent, same as a definite SMTP error
        return;
      }
    }
    try {
      send(c, body);
    } catch (Exception e) {
      fail(c, classify(e), e);
      return;
    }
    if (finish(c, "status = 'SENT', sent_at = now(), last_error = null", "SENT")) meters.counter("notification.sent").increment();
  }

  private void send(Claimed c, String body) throws Exception {
    String id = c.id().toString();
    MimeMessage message = new MimeMessage(mail.getSession()) {
      @Override protected void updateMessageID() throws jakarta.mail.MessagingException { setHeader("Message-ID", "<" + id + "@" + messageIdDomain + ">"); }
    };
    message.setFrom(new InternetAddress(from));
    message.setRecipients(jakarta.mail.Message.RecipientType.TO, InternetAddress.parse(c.recipient(), false));
    message.setSubject(c.subject(), StandardCharsets.UTF_8.name());
    message.setContent(body, "text/html; charset=UTF-8");
    mail.send(message);
  }

  /**
   * Maps the exception to a retry decision. A server reply code wins: 4xx retry, 5xx permanent (SMTPSendFailedException,
   * SMTPSenderFailedException and SMTPAddressFailedException carry it, Spring wraps them in MailSendException). A connect
   * failure means nothing was sent: retry. Anything else (timeout, dropped connection after connecting, no reply code)
   * may have delivered the message: UNKNOWN.
   */
  static Failure classify(Throwable error) {
    // MailSendException keeps the real cause in its failed-messages map, not getCause().
    if (error instanceof MailSendException m && m.getCause() == null && !m.getFailedMessages().isEmpty()) {
      return classify(m.getFailedMessages().values().iterator().next());
    }
    for (Throwable t = error; t != null; t = t.getCause()) {
      int code = switch (t) {
        case SMTPSendFailedException e -> e.getReturnCode();
        case SMTPSenderFailedException e -> e.getReturnCode();
        case SMTPAddressFailedException e -> e.getReturnCode();
        case MailConnectException e -> 400;
        default -> 0;
      };
      if (code >= 500) return Failure.PERMANENT;
      if (code >= 400) return Failure.RETRY;
    }
    return Failure.UNKNOWN;
  }

  private void fail(Claimed c, Failure failure, Exception e) {
    String reason = e.getClass().getSimpleName(); // never the message: it can carry the recipient address
    switch (failure) {
      case UNKNOWN -> {
        if (finish(c, "status = 'UNKNOWN', last_error = '" + reason + "'", "UNKNOWN")) meters.counter("notification.unknown").increment();
      }
      case PERMANENT -> {
        if (finish(c, "status = 'FAILED', last_error = '" + reason + "'", "FAILED")) failed(c);
      }
      case RETRY -> {
        if (c.retryCount() >= MAX_RETRIES) {
          if (finish(c, "retry_count = retry_count + 1, status = 'FAILED', last_error = '" + reason + "'", "FAILED")) failed(c);
        } else {
          double seconds = 60.0 * (1 << c.retryCount()) * (0.8 + 0.4 * ThreadLocalRandom.current().nextDouble());
          finish(c, "retry_count = retry_count + 1, status = 'PENDING', next_retry_at = now() + make_interval(secs => " + seconds
              + "), last_error = '" + reason + "'", "PENDING");
        }
      }
    }
  }

  private void failed(Claimed c) { meters.counter("notification.failed", "template", c.template()).increment(); }

  /** Writes the result only while our lease is still valid; clears the lease (the table CHECK requires it). */
  private boolean finish(Claimed c, String set, String status) {
    int rows = jdbc.sql("update notifications set " + set + ", lease_token = null, lease_until = null "
        + "where id = :id and lease_token = :token and lease_until > now()")
        .param("id", c.id()).param("token", c.leaseToken()).update();
    if (rows == 0) log.warn("notification_id={} dedupe_key={} template={} status={} lease lost, result dropped", c.id(), c.dedupeKey(), c.template(), status);
    else log.info("notification_id={} dedupe_key={} template={} status={}", c.id(), c.dedupeKey(), c.template(), status);
    return rows == 1;
  }
}
