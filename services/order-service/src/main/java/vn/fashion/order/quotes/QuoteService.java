package vn.fashion.order.quotes;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import vn.fashion.order.deps.CartClient;
import vn.fashion.order.deps.CatalogClient;
import vn.fashion.order.deps.ShippingClient;
import vn.fashion.order.orders.OrderActor;
import vn.fashion.order.web.Api;

/** Spec section 5: server prices and fees for the actor's cart; nothing stored, nothing reserved. */
@Service
public class QuoteService {
  private final CartClient carts;
  private final CatalogClient catalog;
  private final ShippingClient shipping;
  private final Clock clock;
  private final Duration ttl;
  private final Set<String> methods;

  public QuoteService(CartClient carts, CatalogClient catalog, ShippingClient shipping, Clock clock,
                      @Value("${fashion.order.quote-ttl:2m}") Duration ttl, @Value("${fashion.order.payment-methods:COD}") String methods) {
    this.carts = carts; this.catalog = catalog; this.shipping = shipping; this.clock = clock; this.ttl = ttl;
    this.methods = Arrays.stream(methods.split(",")).map(String::strip).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
  }

  public Quote quote(OrderActor actor, QuoteRequest request, String locale) {
    requireCart(actor, request.cartId());
    if (!methods.contains(request.method()))
      throw new Api.Problem(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_ERROR", "PAYMENT_METHOD_UNAVAILABLE", List.of());
    var snapshot = carts.snapshot(request.cartId(), request.cartVersion(), actor.key());
    if (snapshot.items().isEmpty()) throw new Api.Problem(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_ERROR", "CART_EMPTY", List.of());
    var lines = lines(snapshot, catalog.quote(snapshot.items().stream().map(CartClient.Item::variantId).toList()), locale);
    long subtotal = 0;
    for (var line : lines) subtotal = add(subtotal, line.lineTotal());
    boolean cod = request.method().equals("COD");
    var fee = shipping.quote(request.address().provinceCode(), cod,
        snapshot.items().stream().map(i -> new ShippingClient.Line(i.variantId(), i.quantity())).toList());
    long codFee = cod ? fee.codFee() : 0;
    long total = add(add(subtotal, fee.fee()), codFee);
    if (total <= 0) throw new Api.Problem(HttpStatus.UNPROCESSABLE_CONTENT, "PAYMENT_AMOUNT_UNSUPPORTED", "PAYMENT_AMOUNT_UNSUPPORTED", List.of());
    return new Quote(actor.key(), request.cartId(), request.cartVersion(), request.method(), request.address(), request.contact(),
        request.note(), locale, lines, new Quote.Shipping(fee.quoteId(), fee.carrier(), fee.weightGrams(), fee.fee(), codFee, fee.expiresAt()),
        new Quote.Totals(subtotal, 0, fee.fee(), 0, codFee, total, "VND"), clock.instant().plus(ttl));
  }

  /** T21: a guest's checkout token names one cart; another cart id is not found (never sent to cart). */
  public static void requireCart(OrderActor actor, UUID cartId) {
    if (!actor.member() && !actor.tokenCartId().equals(cartId))
      throw new Api.Problem(HttpStatus.NOT_FOUND, "NOT_FOUND", "CART_NOT_FOUND", List.of());
  }

  /** Catalog price per cart line; a missing or inactive variant is 409 ITEM_UNAVAILABLE (errors per variant_id). */
  public static List<Quote.Line> lines(CartClient.Snapshot snapshot, Map<UUID, CatalogClient.Variant> variants, String locale) {
    var unavailable = new ArrayList<Api.FieldError>();
    var lines = new ArrayList<Quote.Line>();
    for (var item : snapshot.items()) {
      var v = variants.get(item.variantId());
      if (v == null || !v.found() || !v.active()) { unavailable.add(new Api.FieldError(item.variantId().toString(), "not available")); continue; }
      long lineTotal;
      try { lineTotal = Math.multiplyExact(item.quantity(), v.effectivePrice()); }
      catch (ArithmeticException e) { throw outOfRange(); }
      lines.add(new Quote.Line(item.variantId(), item.itemId(), item.itemVersion(), item.quantity(), v.effectivePrice(), lineTotal,
          v.productVersion(), v.sku(), locale.equals("en") ? v.nameEn() : v.nameVi(), v.size(), v.color(),
          v.imageAssetId() == null ? null : "/api/v1/catalog/images/" + v.imageAssetId() + "?kind=thumb"));
    }
    if (!unavailable.isEmpty()) throw new Api.Problem(HttpStatus.CONFLICT, "CONFLICT", "ITEM_UNAVAILABLE", unavailable);
    return lines;
  }

  static long add(long a, long b) {
    try { return Math.addExact(a, b); } catch (ArithmeticException e) { throw outOfRange(); }
  }
  static Api.Problem outOfRange() {
    return new Api.Problem(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_ERROR", "AMOUNT_OUT_OF_RANGE", List.of());
  }
}
