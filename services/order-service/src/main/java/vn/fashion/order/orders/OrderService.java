package vn.fashion.order.orders;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.fashion.order.deps.CartClient;
import vn.fashion.order.deps.CatalogClient;
import vn.fashion.order.quotes.Quote;
import vn.fashion.order.quotes.QuoteService;
import vn.fashion.order.quotes.QuoteTokens;
import vn.fashion.order.web.Api;

/**
 * Spec section 6: idempotency lookup first (a retry gets its order even after the quote expired), then the quote and a
 * fresh check of cart and prices outside any transaction, then one order-db transaction. No HTTP inside it.
 */
@Service
public class OrderService {
  private static final Logger LOG = LoggerFactory.getLogger(OrderService.class);
  private static final ZoneId SHOP = ZoneId.of("Asia/Ho_Chi_Minh");
  private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");
  static final int RETRY_AFTER_SECONDS = 3;
  public record CreateRequest(UUID cartId, long cartVersion, String quoteToken) { }
  public record Created(OrderView view, boolean replay, String guestAccessToken) { }
  public record Row(UUID id, String orderNo, UUID userId, String actorKey, String guestAccessHash, String checkoutHash, String status,
             String paymentMethod, long subtotal, long discountAmount, long shippingFee, long shippingDiscount, long codFee,
             long totalAmount, String contactEmail, String contactPhone, String shippingAddress, String note, Instant createdAt,
             Instant expiresAt, long version) { }

  private final JdbcClient jdbc;
  private final TransactionTemplate tx;
  private final QuoteTokens tokens;
  private final CartClient carts;
  private final CatalogClient catalog;
  private final OrderEvents events;
  private final ObjectMapper json;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  public OrderService(JdbcClient jdbc, TransactionTemplate tx, QuoteTokens tokens, CartClient carts, CatalogClient catalog,
                      OrderEvents events, ObjectMapper json, Clock clock) {
    this.jdbc = jdbc; this.tx = tx; this.tokens = tokens; this.carts = carts; this.catalog = catalog; this.events = events;
    this.json = json; this.clock = clock;
  }

  public static CreateRequest parse(JsonNode body) {
    var errors = new ArrayList<Api.FieldError>();
    for (String name : body.propertyNames())
      if (!Set.of("cart_id", "cart_version", "quote_token").contains(name)) errors.add(new Api.FieldError(name, "is not allowed"));
    UUID cart = Api.uuid(body.path("cart_id").asString(null));
    if (cart == null) errors.add(new Api.FieldError("cart_id", "must be a lowercase UUID"));
    JsonNode v = body.path("cart_version");
    if (!v.isIntegralNumber() || !v.canConvertToLong() || v.longValue() < 0) errors.add(new Api.FieldError("cart_version", "must be an integer >= 0"));
    String token = body.path("quote_token").asString(null);
    if (token == null || token.isBlank() || token.length() > 65536) errors.add(new Api.FieldError("quote_token", "is required"));
    if (!errors.isEmpty()) throw new Api.Problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "INVALID_FIELDS", errors);
    return new CreateRequest(cart, v.longValue(), token);
  }

  public Created create(OrderActor actor, String key, CreateRequest request, String correlationId) {
    QuoteService.requireCart(actor, request.cartId());
    String hash = Api.sha256(request.cartId() + "|" + request.cartVersion() + "|" + Api.sha256(request.quoteToken()));
    var existing = byCheckout(actor.key(), key);
    if (existing.isPresent()) return replay(existing.get(), hash);

    Quote quote = tokens.verify(request.quoteToken());
    if (!quote.actorKey().equals(actor.key()) || !quote.cartId().equals(request.cartId()) || quote.cartVersion() != request.cartVersion())
      throw QuoteTokens.invalid();
    if (quote.expired(clock.instant())) throw new Api.Problem(HttpStatus.CONFLICT, "QUOTE_EXPIRED", "QUOTE_EXPIRED", List.of());
    recheck(actor, quote);

    UUID id = UUID.randomUUID();
    String access = actor.member() ? null : newAccessToken();
    boolean inserted = Boolean.TRUE.equals(tx.execute(status -> insert(id, actor, key, hash, quote, access, correlationId)));
    if (inserted) return new Created(view(id), false, access);
    // Lost a race: the same key committed first (replay), or another key already ordered this cart state.
    var winner = byCheckout(actor.key(), key);
    if (winner.isPresent()) return replay(winner.get(), hash);
    var ordered = jdbc.sql("select order_no, actor_key from orders where cart_id = :c and cart_version = :v")
        .param("c", request.cartId()).param("v", request.cartVersion()).query((rs, n) -> new String[] {rs.getString(1), rs.getString(2)})
        .optional().orElseThrow(() -> new IllegalStateException("insert skipped without a conflicting order"));
    throw new Api.Problem(HttpStatus.CONFLICT, "CONFLICT", "CART_ALREADY_ORDERED",
        List.of(new Api.FieldError("order_no", ordered[1].equals(actor.key()) ? ordered[0] : "")));
  }

  /** The cart must still hold exactly the quoted lines and catalog the quoted prices (T24), else nothing is written. */
  private void recheck(OrderActor actor, Quote quote) {
    var snapshot = carts.snapshot(quote.cartId(), quote.cartVersion(), actor.key());
    Map<UUID, String> now = new HashMap<>(), quoted = new HashMap<>();
    snapshot.items().forEach(i -> now.put(i.itemId(), i.variantId() + ":" + i.quantity() + ":" + i.itemVersion()));
    quote.items().forEach(l -> quoted.put(l.cartItemId(), l.variantId() + ":" + l.quantity() + ":" + l.cartItemVersion()));
    if (!now.equals(quoted))
      throw new Api.Problem(HttpStatus.CONFLICT, "VERSION_CONFLICT", "VERSION_CONFLICT",
          List.of(new Api.FieldError("cart_version", "the cart changed; quote again")));
    var lines = QuoteService.lines(snapshot, catalog.quote(snapshot.items().stream().map(CartClient.Item::variantId).toList()),
        quote.locale());
    Map<UUID, Long> price = new HashMap<>();
    quote.items().forEach(l -> price.put(l.variantId(), l.unitPrice()));
    var changed = new ArrayList<Api.FieldError>();
    for (var line : lines)
      if (!Objects.equals(price.get(line.variantId()), line.unitPrice()))
        changed.add(new Api.FieldError(line.variantId().toString(), "old=" + price.get(line.variantId()) + ",new=" + line.unitPrice()));
    if (!changed.isEmpty()) throw new Api.Problem(HttpStatus.CONFLICT, "PRICE_CHANGED", "PRICE_CHANGED", changed);
  }

  private boolean insert(UUID id, OrderActor actor, String key, String hash, Quote quote, String access, String correlationId) {
    Instant now = clock.instant();
    long seq = jdbc.sql("select nextval('order_no_seq')").query(Long.class).single();
    String orderNo = "ORD" + MONTH.format(now.atZone(SHOP)) + String.format("%06d", seq);
    // COD waits 24 h for OPS (06 section 5.2); online methods get 15 minutes (ORD-03 owns the expiry).
    Instant expires = now.plus(quote.method().equals("COD") ? Duration.ofHours(24) : Duration.ofMinutes(15));
    var t = quote.totals();
    int rows = jdbc.sql("""
        insert into orders(id, order_no, user_id, actor_key, guest_access_hash, checkout_key, checkout_hash, cart_id, cart_version,
          payment_method, subtotal, discount_amount, shipping_fee, shipping_discount, cod_fee, total_amount, contact_email,
          contact_phone, locale, shipping_address, shipping_quote, note, expires_at, created_at, updated_at)
        values (:id, :no, :user, :actor, :access, :key, :hash, :cart, :cartVersion, :method, :subtotal, :discount, :fee, :feeDiscount,
          :codFee, :total, :email, :phone, :locale, cast(:address as jsonb), cast(:shipping as jsonb), :note, :expires, :now, :now)
        on conflict do nothing""")
        .param("id", id).param("no", orderNo).param("user", actor.userId()).param("actor", actor.key())
        .param("access", access == null ? null : Api.sha256(access)).param("key", key).param("hash", hash)
        .param("cart", quote.cartId()).param("cartVersion", quote.cartVersion()).param("method", quote.method())
        .param("subtotal", t.subtotal()).param("discount", t.discountAmount()).param("fee", t.shippingFee())
        .param("feeDiscount", t.shippingDiscount()).param("codFee", t.codFee()).param("total", t.totalAmount())
        .param("email", quote.contact().email()).param("phone", quote.contact().phone()).param("locale", quote.locale())
        .param("address", json.writeValueAsString(quote.shippingAddress())).param("shipping", json.writeValueAsString(quote.shipping()))
        .param("note", quote.note()).param("expires", Timestamp.from(expires)).param("now", Timestamp.from(now)).update();
    if (rows == 0) return false;
    long subtotal = 0;
    for (var line : quote.items()) {
      subtotal += line.lineTotal();
      jdbc.sql("""
          insert into order_items(id, order_id, variant_id, sku, name, size, color, image_url, quantity, unit_price, line_total,
            catalog_version, cart_item_id, cart_item_version)
          values (:id, :order, :variant, :sku, :name, :size, :color, :image, :qty, :price, :total, :catalogVersion, :item, :itemVersion)""")
          .param("id", UUID.randomUUID()).param("order", id).param("variant", line.variantId()).param("sku", line.sku())
          .param("name", line.name()).param("size", line.size()).param("color", line.color()).param("image", line.imageUrl())
          .param("qty", line.quantity()).param("price", line.unitPrice()).param("total", line.lineTotal())
          .param("catalogVersion", line.productVersion()).param("item", line.cartItemId()).param("itemVersion", line.cartItemVersion())
          .update();
    }
    // 05 section 7: no cross-table CHECK, so the subtotal is verified here inside the transaction.
    if (subtotal != t.subtotal()) throw new IllegalStateException("quote subtotal does not match its lines");
    jdbc.sql("""
        insert into order_status_history(id, order_id, from_status, to_status, actor_type, actor_id)
        values (:id, :order, null, 'PENDING', 'CUSTOMER', :actor)""")
        .param("id", UUID.randomUUID()).param("order", id).param("actor", actor.userId()).update();
    jdbc.sql("insert into order_sagas(order_id, intent, step, status) values (:order, 'FULFILL', 'RESERVE', 'RUNNING')")
        .param("order", id).update();
    if (access != null) grantAccess(id, access);
    events.created(view(id), actor.userId(), correlationId);
    return true;
  }

  /**
   * Same key: the same request gets its order; another body is 409. A guest gets one more access cookie; earlier ones stay
   * valid (review I1: racing responses may reach the browser in any order, and a lost one never reached anybody).
   */
  private Created replay(Row row, String hash) {
    if (!row.checkoutHash().equals(hash)) throw new Api.Problem(HttpStatus.CONFLICT, "CONFLICT", "IDEMPOTENCY_KEY_REUSED", List.of());
    String access = null;
    if (row.userId() == null) {
      access = newAccessToken();
      grantAccess(row.id(), access);
      LOG.info("Guest order access reissued: order_no={} event=ACCESS_REISSUED", row.orderNo());
    }
    return new Created(view(row.id()), true, access);
  }

  private void grantAccess(UUID orderId, String access) {
    jdbc.sql("insert into order_guest_access(access_hash, order_id) values (:h, :o)").param("h", Api.sha256(access)).param("o", orderId)
        .update();
  }

  Optional<Row> byCheckout(String actorKey, String key) {
    return jdbc.sql(ROW + " where actor_key = :a and checkout_key = :k").param("a", actorKey).param("k", key).query(Row.class).optional();
  }
  public Optional<Row> byNo(String orderNo) {
    return jdbc.sql(ROW + " where order_no = :no").param("no", orderNo).query(Row.class).optional();
  }

  public OrderView view(UUID id) { return view(jdbc.sql(ROW + " where id = :id").param("id", id).query(Row.class).single()); }

  OrderView view(Row row) {
    var items = jdbc.sql("""
        select variant_id, sku, name, size, color, image_url, quantity, unit_price, line_total from order_items
        where order_id = :id order by sku, variant_id""").param("id", row.id()).query(OrderController.Item.class).list();
    boolean processing = row.status().equals("PENDING");
    return new OrderView(row.id(), row.orderNo(), row.status(), processing ? "PROCESSING" : "READY", "/api/v1/orders/" + row.orderNo(),
        processing ? RETRY_AFTER_SECONDS : null, row.version(), row.paymentMethod(), items,
        new Quote.Totals(row.subtotal(), row.discountAmount(), row.shippingFee(), row.shippingDiscount(), row.codFee(), row.totalAmount(), "VND"),
        json.readValue(row.shippingAddress(), Quote.Address.class), new Quote.Contact(row.contactPhone(), row.contactEmail()), row.note(),
        row.createdAt(), row.expiresAt(), List.of());
  }

  public record Summary(String orderNo, String status, long totalAmount, String currency, Instant createdAt, int itemCount) { }
  public record Page(List<Summary> items, String nextCursor) { }
  static final Set<String> STATUSES = Set.of("PENDING", "WAITING_PAYMENT", "CONFIRMED", "PAID", "PACKING", "SHIPPING", "DELIVERED",
      "CANCELLING", "CANCELLED", "REFUNDING", "REFUNDED", "RETURNING", "RETURNED");

  /** Spec section 8: member by user_id, guest by its access cookie; anything else is the same 404 (T21). */
  public OrderView readable(String orderNo, UUID userId, String accessToken) {
    var row = byNo(orderNo).filter(r -> userId != null ? userId.equals(r.userId())
        : accessToken != null && r.userId() == null && jdbc.sql("select count(*) from order_guest_access where access_hash = :h and order_id = :o")
            .param("h", Api.sha256(accessToken)).param("o", r.id()).query(Long.class).single() > 0);
    return view(row.orElseThrow(() -> new Api.Problem(HttpStatus.NOT_FOUND, "NOT_FOUND", "ORDER_NOT_FOUND", List.of())));
  }

  /** Newest first, cursor = base64url(status filter | created_at micros | id) so it cannot be reused with another filter. */
  public Page mine(UUID userId, String status, String cursor, int limit) {
    if (status != null && !STATUSES.contains(status))
      throw new Api.Problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "INVALID_FIELDS", List.of(new Api.FieldError("status", "unknown status")));
    if (limit < 1 || limit > 100)
      throw new Api.Problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "INVALID_FIELDS", List.of(new Api.FieldError("limit", "must be 1..100")));
    Instant after = null;
    UUID afterId = null;
    if (cursor != null) {
      try {
        String[] parts = new String(Base64.getUrlDecoder().decode(cursor), java.nio.charset.StandardCharsets.UTF_8).split("[|]", -1);
        if (parts.length != 3 || !parts[0].equals(status == null ? "" : status)) throw new IllegalArgumentException();
        long micros = Long.parseLong(parts[1]);
        after = Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000), Math.floorMod(micros, 1_000_000) * 1000L);
        afterId = UUID.fromString(parts[2]);
      } catch (RuntimeException e) { throw new Api.Problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "INVALID_CURSOR", List.of()); }
    }
    record Item(UUID id, String orderNo, String status, long totalAmount, Instant createdAt, int itemCount) { }
    var rows = jdbc.sql("""
        select o.id, o.order_no, o.status, o.total_amount, o.created_at, (select count(*) from order_items i where i.order_id = o.id) as item_count
        from orders o where o.user_id = :u and (cast(:s as text) is null or o.status = :s)
          and (cast(:after as timestamptz) is null or (o.created_at, o.id) < (cast(:after as timestamptz), :afterId))
        order by o.created_at desc, o.id desc limit :n""")
        .param("u", userId).param("s", status).param("after", after == null ? null : Timestamp.from(after))
        .param("afterId", afterId).param("n", limit + 1).query(Item.class).list();
    String next = null;
    if (rows.size() > limit) {
      var last = rows.get(limit - 1);
      long micros = last.createdAt().getEpochSecond() * 1_000_000 + last.createdAt().getNano() / 1000;
      next = Base64.getUrlEncoder().withoutPadding().encodeToString(((status == null ? "" : status) + "|" + micros + "|" + last.id())
          .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    return new Page(rows.stream().limit(limit).map(r -> new Summary(r.orderNo(), r.status(), r.totalAmount(), "VND", r.createdAt(),
        r.itemCount())).toList(), next);
  }

  private String newAccessToken() {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static final String ROW = """
      select id, order_no, user_id, actor_key, guest_access_hash, checkout_hash, status, payment_method, subtotal, discount_amount,
        shipping_fee, shipping_discount, cod_fee, total_amount, contact_email, contact_phone, shipping_address::text as shipping_address,
        note, created_at, expires_at, version from orders""";
}
