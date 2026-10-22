package vn.fashion.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import vn.fashion.platform.messaging.PermanentEventException;

class NotificationIntakeIntegrationTest extends NotificationTestSupport {
  private static final String ORDER = "11111111-1111-1111-1111-111111111111";
  private static final String SHIP = ",\"ship_to\":{\"name\":\"Nguyễn Văn An\",\"phone_masked\":\"090****123\",\"address\":\"12 Phố Mẫu\"}";

  private static String item(String name) {
    return "{\"name\":\"" + name + "\",\"size\":\"M\",\"color\":\"Trắng\",\"quantity\":2,\"line_total\":398000,\"image_url\":\"/api/v1/catalog/images/a1\"}";
  }
  private static String payload(String kind, String locale, String name, String extra) {
    return "{\"dedupe_key\":\"order:" + ORDER + ":" + kind.toLowerCase() + "\",\"user_id\":null,\"recipient\":{\"email\":\"an@example.invalid\"},"
        + "\"channel\":\"EMAIL\",\"locale\":\"" + locale + "\",\"template_key\":\"ORDER_" + kind + "\",\"data\":{\"order_no\":\"FS260122-0042\","
        + "\"placed_at\":\"2026-10-22T02:15:00Z\",\"items\":[" + item(name) + "],\"subtotal\":857000,\"shipping_fee\":30000,\"total\":887000,"
        + "\"payment_method\":\"COD\"" + extra + "}}";
  }

  private Map<String, Object> only() { return jdbc.queryForMap("select * from notifications"); }

  @Test void migrationsSeedSixKeysInBothLocalesAndEnforceConstraints() {
    assertThat(count("select count(*) from notification_templates where channel = 'EMAIL'")).isEqualTo(12);
    assertThat(count("select count(distinct template_key) from notification_templates")).isEqualTo(6);
    assertThat(jdbc.queryForObject("select body from notification_templates where template_key = 'RESET_PASSWORD' and locale = 'vi'", String.class))
        .contains("@@RESET_URL@@");
    deliver(envelope(UUID.randomUUID(), "NOTIFY_ORDER_CONFIRMED", payload("CONFIRMED", "vi", "Áo", SHIP)));
    assertThatThrownBy(() -> jdbc.update("update notifications set status = 'BOGUS'")).hasMessageContaining("status");
    assertThatThrownBy(() -> jdbc.update("insert into notifications (id, event_id, dedupe_key, recipient, channel, template_id, template_version, "
        + "rendered_body, locale) select gen_random_uuid(), gen_random_uuid(), dedupe_key, recipient, channel, template_id, 1, 'b', 'vi' "
        + "from notifications")).hasMessageContaining("notifications_dedupe_key_channel_recipient_key");
  }

  @Test void anOrderEventBecomesOnePendingEmail() {
    deliver(envelope(UUID.randomUUID(), "NOTIFY_ORDER_CONFIRMED", payload("CONFIRMED", "vi", "Áo thun", SHIP)));
    var row = only();
    assertThat(row).containsEntry("status", "PENDING").containsEntry("recipient", "an@example.invalid").containsEntry("template_version", 1);
    assertThat((String) row.get("rendered_subject")).contains("FS260122-0042");
    assertThat((String) row.get("rendered_body")).contains("887.000 ₫", "090****123", "https://cdn.example.invalid/api/v1/catalog/images/a1",
        "https://shop.example.invalid/orders/FS260122-0042", "24 giờ", "09:15 22/10/2026");
  }

  @Test void theSameEventTwiceOrTheSameDedupeKeyIsOneRow() {
    UUID event = UUID.randomUUID();
    String body = payload("CONFIRMED", "vi", "Áo thun", SHIP);
    deliver(envelope(event, "NOTIFY_ORDER_CONFIRMED", body));
    deliver(envelope(event, "NOTIFY_ORDER_CONFIRMED", body));
    deliver(envelope(UUID.randomUUID(), "NOTIFY_ORDER_CONFIRMED", body));
    assertThat(count("select count(*) from notifications")).isEqualTo(1);
    assertThat(count("select count(*) from processed_events")).isEqualTo(2);
  }

  @Test void englishUsesEnglish() {
    deliver(envelope(UUID.randomUUID(), "NOTIFY_ORDER_CONFIRMED", payload("CONFIRMED", "en", "Tee", SHIP)));
    assertThat((String) only().get("rendered_body")).contains("887,000 VND", "within 24 hours");
  }

  @Test void englishFallsBackToVietnameseWhenMissing() {
    asOwner("update notification_templates set active = false where template_key = 'ORDER_PREPARING' and locale = 'en'");
    deliver(envelope(UUID.randomUUID(), "NOTIFY_ORDER_PREPARING", payload("PREPARING", "en", "Tee", "")));
    var row = only();
    assertThat((String) row.get("rendered_body")).contains("398.000 ₫", "đang được chuẩn bị");
    assertThat(row.get("locale")).isEqualTo("en");
  }

  @Test void renderedDataIsEscaped() {
    deliver(envelope(UUID.randomUUID(), "NOTIFY_ORDER_PREPARING", payload("PREPARING", "vi", "<script>x</script>{{y}}", "")));
    assertThat((String) only().get("rendered_body")).contains("&lt;script&gt;", "{{y}}").doesNotContain("<script>");
  }

  @ParameterizedTest
  @CsvSource(delimiter = '|', value = {
      "CUSTOMER|Bạn đã hủy đơn.", "SHOP|Shop đã hủy đơn, vui lòng liên hệ hotline nếu cần hỗ trợ.", "EXPIRED|Đơn quá hạn xác nhận.",
      "OUT_OF_STOCK|Sản phẩm đã hết hàng.", "OTHER|Đơn không thể xử lý, vui lòng liên hệ hotline."})
  void cancelledShowsTheFixedSentence(String category, String sentence) {
    deliver(envelope(UUID.randomUUID(), "NOTIFY_ORDER_CANCELLED", payload("CANCELLED", "vi", "Áo", ",\"cancel_category\":\"" + category + "\"")));
    assertThat((String) only().get("rendered_body")).contains(sentence);
  }

  @Test void anInvalidEventIsPermanent() {
    String noRecipient = payload("CONFIRMED", "vi", "Áo", SHIP).replace("\"recipient\":{\"email\":\"an@example.invalid\"},", "");
    assertThatThrownBy(() -> deliver(envelope(UUID.randomUUID(), "NOTIFY_ORDER_CONFIRMED", noRecipient))).isInstanceOf(PermanentEventException.class);
    assertThat(count("select count(*) from notifications")).isZero();
  }
}
