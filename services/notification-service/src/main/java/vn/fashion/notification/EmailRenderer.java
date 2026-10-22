package vn.fashion.notification;

import com.samskivert.mustache.Mustache;
import com.samskivert.mustache.Template;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import vn.fashion.platform.messaging.PermanentEventException;

/**
 * NOT-01: renders a stored template (DB subject/body, classpath layout and partials) with event data. Every value is
 * HTML-escaped by Mustache; the body is rendered first and then wrapped by the layout as trusted markup.
 */
@Component
public class EmailRenderer {
  public record Rendered(UUID templateId, int version, String subject, String html) { }
  private record Row(UUID id, int version, String subject, String body, String locale) { }

  private static final DateTimeFormatter PLACED = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy").withZone(ZoneId.of("Asia/Ho_Chi_Minh"));
  private static final Map<String, Map<String, String>> LABELS = Map.of(
      "vi", Map.of("subtotal", "Tạm tính", "shipping", "Phí giao hàng", "cod", "Thanh toán khi nhận hàng (COD)", "ship_to", "Giao tới",
          "view_order", "XEM ĐƠN HÀNG", "hotline", "Hotline", "auto_note", "Email tự động, vui lòng không trả lời."),
      "en", Map.of("subtotal", "Subtotal", "shipping", "Shipping fee", "cod", "Cash on delivery (COD)", "ship_to", "Ship to",
          "view_order", "VIEW ORDER", "hotline", "Hotline", "auto_note", "Automated email, please do not reply."));
  /** Fixed sentences per cancel_category; the free-text reason of OPS never reaches an email. */
  private static final Map<String, Map<String, String>> CANCEL = Map.of(
      "vi", Map.of("CUSTOMER", "Bạn đã hủy đơn.", "SHOP", "Shop đã hủy đơn, vui lòng liên hệ hotline nếu cần hỗ trợ.",
          "EXPIRED", "Đơn quá hạn xác nhận.", "OUT_OF_STOCK", "Sản phẩm đã hết hàng.",
          "OTHER", "Đơn không thể xử lý, vui lòng liên hệ hotline."),
      "en", Map.of("CUSTOMER", "You cancelled the order.", "SHOP", "The shop cancelled the order, please call the hotline if you need help.",
          "EXPIRED", "The order was not confirmed in time.", "OUT_OF_STOCK", "An item is out of stock.",
          "OTHER", "The order could not be processed, please call the hotline."));

  private final JdbcClient jdbc;
  private final Mustache.Compiler html = Mustache.compiler().defaultValue("").withLoader(EmailRenderer::partial);
  private final Mustache.Compiler text = Mustache.compiler().defaultValue("").escapeHTML(false);
  private final Template layout;
  private final String shopName, hotline, storefront, assets;

  public EmailRenderer(JdbcClient jdbc, @Value("${fashion.notification.shop-name}") String shopName,
                       @Value("${fashion.notification.hotline}") String hotline,
                       @Value("${fashion.notification.storefront-base-url}") String storefront,
                       @Value("${fashion.notification.public-asset-base-url}") String assets) {
    this.jdbc = jdbc; this.shopName = shopName; this.hotline = hotline; this.storefront = trim(storefront); this.assets = trim(assets);
    layout = html.compile(partial("layout"));
  }

  public Rendered render(String templateKey, String locale, JsonNode data) {
    Row row = find(templateKey, locale).or(() -> find(templateKey, "vi"))
        .orElseThrow(() -> new PermanentEventException("No email template for " + templateKey));
    Map<String, Object> model = new HashMap<>();
    model.put("locale", row.locale());
    model.put("shop_name", shopName);
    model.put("hotline", hotline);
    model.put("labels", LABELS.get(row.locale()));
    if (data != null && data.has("order_no")) orderModel(model, row.locale(), data);
    String subject = text.compile(row.subject()).execute(model);
    model.put("content", html.compile(row.body()).execute(model));
    model.put("subject", subject);
    return new Rendered(row.id(), row.version(), subject, layout.execute(model));
  }

  private Optional<Row> find(String key, String locale) {
    return jdbc.sql("select id, version, subject, body, locale from notification_templates "
        + "where channel = 'EMAIL' and template_key = :k and locale = :l and active").param("k", key).param("l", locale)
        .query(Row.class).optional();
  }

  private void orderModel(Map<String, Object> model, String locale, JsonNode d) {
    String orderNo = d.path("order_no").asString();
    model.put("order_no", orderNo);
    model.put("order_url", storefront + "/orders/" + URLEncoder.encode(orderNo, StandardCharsets.UTF_8));
    model.put("placed_at", PLACED.format(Instant.parse(d.path("placed_at").asString())));
    model.put("subtotal", money(d.path("subtotal").longValue(), locale));
    model.put("shipping_fee", money(d.path("shipping_fee").longValue(), locale));
    model.put("total", money(d.path("total").longValue(), locale));
    List<Map<String, Object>> items = new ArrayList<>();
    for (JsonNode i : d.path("items")) {
      String image = image(i.path("image_url").asString(""));
      items.add(Map.of("name", i.path("name").asString(), "variant", i.path("size").asString("") + " / " + i.path("color").asString(""),
          "quantity", i.path("quantity").intValue(), "line_total", money(i.path("line_total").longValue(), locale),
          "has_image", !image.isEmpty(), "image", image));
    }
    model.put("items", items);
    JsonNode ship = d.path("ship_to");
    if (ship.isObject()) model.put("ship_to", Map.of("name", ship.path("name").asString(""),
        "phone_masked", ship.path("phone_masked").asString(""), "address", ship.path("address").asString("")));
    String category = d.path("cancel_category").asString("");
    if (!category.isEmpty()) model.put("cancel_reason", CANCEL.get(locale).getOrDefault(category, CANCEL.get(locale).get("OTHER")));
  }

  /** Absolute URLs stay as-is, relative ones are joined to the public asset base; blank means no picture. */
  private String image(String url) {
    if (url.isBlank()) return "";
    if (url.startsWith("http://") || url.startsWith("https://")) return url;
    return assets + (url.startsWith("/") ? "" : "/") + url;
  }

  /** Whole VND: vi 887.000 ₫, en 887,000 VND. */
  static String money(long amount, String locale) {
    String grouped = String.format(Locale.ROOT, "%,d", amount);
    return "vi".equals(locale) ? grouped.replace(',', '.') + " ₫" : grouped + " VND";
  }

  private static String trim(String url) { return url.endsWith("/") ? url.substring(0, url.length() - 1) : url; }

  private static Reader partial(String name) {
    var in = EmailRenderer.class.getResourceAsStream("/templates/email/" + name + ".mustache");
    if (in == null) throw new IllegalStateException("Missing email partial " + name);
    return new InputStreamReader(in, StandardCharsets.UTF_8);
  }
}
