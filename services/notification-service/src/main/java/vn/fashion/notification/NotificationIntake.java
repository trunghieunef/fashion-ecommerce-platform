package vn.fashion.notification;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import vn.fashion.platform.messaging.EventEnvelope;
import vn.fashion.platform.messaging.PermanentEventException;

/**
 * NOT-01 spec section 5.1: one NOTIFY_ORDER_* event becomes one PENDING email row with the rendered subject/body as a
 * snapshot. Called inside the inbox transaction (InboxGuard.applyOnce). The same (dedupe_key, channel, recipient) is a no-op.
 */
@Component
public class NotificationIntake {
  private static final Set<String> KEYS = Set.of("ORDER_CONFIRMED", "ORDER_PREPARING", "ORDER_DELIVERED", "ORDER_CANCELLED", "ORDER_RETURNED");
  private final JdbcClient jdbc;
  private final EmailRenderer renderer;

  public NotificationIntake(JdbcClient jdbc, EmailRenderer renderer) { this.jdbc = jdbc; this.renderer = renderer; }

  public Object accept(EventEnvelope event) {
    JsonNode p = event.payload();
    String key = text(p, "template_key"), dedupe = text(p, "dedupe_key"), locale = text(p, "locale");
    boolean reset = key.equals("RESET_PASSWORD");
    JsonNode data = reset ? null : p.path("data");
    if (!(reset || KEYS.contains(key)) || !event.eventType().equals("NOTIFY_" + key) || !"EMAIL".equals(p.path("channel").asString(null))
        || !(locale.equals("vi") || locale.equals("en")) || !(reset || data.isObject()) || !p.path("recipient").isObject()) throw invalid();
    Instant expiresAt = null;
    if (reset) {
      // The challenge_id is the secret reference; the sender reads it back from the dedupe_key, so they must agree.
      try {
        expiresAt = Instant.parse(text(p, "expires_at"));
        if (!dedupe.equals("RESET_PASSWORD:" + UUID.fromString(text(p, "challenge_id")))) throw invalid();
      } catch (RuntimeException e) { throw invalid(); }
    }
    String email = text(p.path("recipient"), "email");
    if (email.length() < 3 || email.length() > 254 || email.indexOf('@') < 0 || email.contains("\r") || email.contains("\n")) throw invalid();
    UUID user = null;
    JsonNode u = p.path("user_id");
    if (!u.isMissingNode() && !u.isNull()) {
      try { user = UUID.fromString(u.asString()); } catch (RuntimeException e) { throw invalid(); }
    }
    if (!reset) checkData(data, key);
    var rendered = renderer.render(key, locale, data);
    jdbc.sql("""
        insert into notifications (id, event_id, dedupe_key, user_id, recipient, channel, template_id, template_version,
          rendered_subject, rendered_body, locale, expires_at)
        values (:id, :event, :dedupe, :user, :to, 'EMAIL', :tpl, :version, :subject, :body, :locale, :expires)
        on conflict (dedupe_key, channel, recipient) do nothing""")
        .param("id", UUID.randomUUID()).param("event", event.eventId()).param("dedupe", dedupe).param("user", user)
        .param("to", email).param("tpl", rendered.templateId()).param("version", rendered.version())
        .param("subject", rendered.subject()).param("body", rendered.html()).param("locale", locale)
        .param("expires", expiresAt == null ? null : java.sql.Timestamp.from(expiresAt)).update();
    return null;
  }

  /** No JSON Schema validator at runtime: check what the renderer and the row need. */
  private static void checkData(JsonNode d, String key) {
    text(d, "order_no");
    try { Instant.parse(text(d, "placed_at")); } catch (RuntimeException e) { throw invalid(); }
    for (String f : new String[] {"subtotal", "shipping_fee", "total"}) amount(d.path(f));
    if (!d.path("items").isArray() || d.path("items").isEmpty()) throw invalid();
    for (JsonNode i : d.path("items")) {
      text(i, "name");
      amount(i.path("line_total"));
      if (!i.path("quantity").isIntegralNumber() || i.path("quantity").intValue() < 1) throw invalid();
    }
    if (key.equals("ORDER_CONFIRMED") && !d.path("ship_to").isObject()) throw invalid();
    if (key.equals("ORDER_CANCELLED") && !d.path("cancel_category").isString()) throw invalid();
  }
  private static void amount(JsonNode n) { if (!n.isIntegralNumber() || !n.canConvertToLong() || n.longValue() < 0) throw invalid(); }
  private static String text(JsonNode n, String field) {
    JsonNode v = n.path(field);
    if (!v.isString() || v.asString().isBlank()) throw invalid();
    return v.asString();
  }
  private static PermanentEventException invalid() { return new PermanentEventException("Invalid notification event"); }
}
