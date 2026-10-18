package vn.fashion.cart.cart;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import vn.fashion.cart.deps.CatalogClient;
import vn.fashion.cart.deps.StockClient;
import vn.fashion.cart.web.Api;
import vn.fashion.platform.idempotency.IdempotencyStore;

/**
 * Spec section 6: read the cart, call catalog/inventory, then one short transaction that locks the cart and
 * re-checks the version. No HTTP while a row lock is held. Add is idempotent per actor + cart + key.
 */
@Service
public class CartItemsService {
  static final int MAX_ITEMS = 100, MAX_QUANTITY = 99;
  private final JdbcClient jdbc;
  private final TransactionTemplate tx;
  private final CartRepository carts;
  private final CatalogClient catalog;
  private final StockClient stock;
  private final IdempotencyStore keys;
  private final ObjectMapper json;

  public CartItemsService(JdbcClient jdbc, TransactionTemplate tx, CartRepository carts, CatalogClient catalog,
                          StockClient stock, IdempotencyStore keys, ObjectMapper json) {
    this.jdbc = jdbc; this.tx = tx; this.carts = carts; this.catalog = catalog; this.stock = stock; this.keys = keys; this.json = json;
  }

  /** Returns either a fresh CartView or the stored response of an earlier identical request. */
  public Object add(CartActor actor, UUID cartId, String key, CartRequests.Add r) {
    CartRequests.requireKey(key);
    String operation = "cart.item.add:" + cartId;
    String hash = sha256(cartId + "|" + r.variantId() + "|" + r.quantity() + "|" + r.expectedVersion());
    var stored = stored(actor, operation, key, hash);
    if (stored.isPresent()) return stored.get();
    var cart = carts.findOwned(cartId, actor).orElseThrow(CartRepository::notFound);
    requireVersion(r.expectedVersion(), cart.version());
    var items = carts.items(cartId);
    var existing = items.stream().filter(i -> i.variantId().equals(r.variantId())).findFirst();
    int total = existing.map(CartRepository.CartItemRow::quantity).orElse(0) + r.quantity();
    if (total > MAX_QUANTITY) throw limit("QUANTITY_LIMIT", "quantity", "total must be at most 99");
    if (existing.isEmpty() && items.size() >= MAX_ITEMS) throw limit("CART_ITEM_LIMIT", "variant_id", "at most 100 items");
    var quote = sellable(r.variantId());
    requireStock(quote.sku(), total);
    return tx.execute(status -> {
      var outcome = keys.begin(actor.key(), operation, key, hash);
      if (outcome.status() == IdempotencyStore.Status.CONFLICT) throw keyReused();
      if (outcome.status() == IdempotencyStore.Status.COMPLETED) return (Object) json.readTree(outcome.responseBody());
      var locked = carts.lockOwned(cartId, actor);
      requireVersion(r.expectedVersion(), locked.version());
      if (existing.isPresent()) {
        jdbc.sql("update cart_items set quantity = :q, price_snapshot = :p, catalog_version = :v, version = version + 1, updated_at = now() where id = :id")
            .param("q", total).param("p", quote.effectivePrice()).param("v", quote.productVersion()).param("id", existing.get().id()).update();
      } else {
        jdbc.sql("""
            insert into cart_items(id, cart_id, variant_id, sku, quantity, price_snapshot, catalog_version)
            values (:id, :cart, :variant, :sku, :q, :p, :v)""")
            .param("id", UUID.randomUUID()).param("cart", cartId).param("variant", r.variantId()).param("sku", quote.sku())
            .param("q", total).param("p", quote.effectivePrice()).param("v", quote.productVersion()).update();
      }
      var view = bump(cartId, actor);
      keys.finish(actor.key(), operation, key, cartId, 200, json.writeValueAsString(view));
      return view;
    });
  }

  public CartView put(CartActor actor, UUID cartId, UUID itemId, CartRequests.Put r) {
    var cart = carts.findOwned(cartId, actor).orElseThrow(CartRepository::notFound);
    requireVersion(r.expectedVersion(), cart.version());
    var item = carts.items(cartId).stream().filter(i -> i.id().equals(itemId)).findFirst().orElseThrow(CartItemsService::itemNotFound);
    if (r.quantity() > item.quantity()) {
      sellable(item.variantId());
      requireStock(item.sku(), r.quantity());
    }
    return tx.execute(status -> {
      requireVersion(r.expectedVersion(), carts.lockOwned(cartId, actor).version());
      if (jdbc.sql("update cart_items set quantity = :q, version = version + 1, updated_at = now() where id = :id and cart_id = :cart")
          .param("q", r.quantity()).param("id", itemId).param("cart", cartId).update() != 1) throw itemNotFound();
      return bump(cartId, actor);
    });
  }

  public CartView remove(CartActor actor, UUID cartId, UUID itemId, long expectedVersion) {
    return tx.execute(status -> {
      requireVersion(expectedVersion, carts.lockOwned(cartId, actor).version());
      if (jdbc.sql("delete from cart_items where id = :id and cart_id = :cart").param("id", itemId).param("cart", cartId).update() != 1)
        throw itemNotFound();
      return bump(cartId, actor);
    });
  }

  /** Customer clears the cart; it stays ACTIVE (the member's unique ACTIVE cart is kept). */
  public CartView clear(CartActor actor, UUID cartId, long expectedVersion) {
    return tx.execute(status -> {
      requireVersion(expectedVersion, carts.lockOwned(cartId, actor).version());
      jdbc.sql("delete from cart_items where cart_id = :cart").param("cart", cartId).update();
      return bump(cartId, actor);
    });
  }

  private CartView bump(UUID cartId, CartActor actor) {
    jdbc.sql("update carts set version = version + 1, updated_at = now() where id = :id").param("id", cartId).update();
    carts.touch(cartId);
    var cart = carts.findOwned(cartId, actor).orElseThrow(CartRepository::notFound);
    return CartView.of(cart, carts.items(cartId), Map.of(), "NOT_LOADED");
  }

  /** Completed identical request → its stored body; same key with another body → 409; else empty. */
  private Optional<Object> stored(CartActor actor, String operation, String key, String hash) {
    return jdbc.sql("""
        select request_hash, status, response_body::text as body from idempotency_requests
        where actor_key = :actor and operation = :op and key = :key""")
        .param("actor", actor.key()).param("op", operation).param("key", key)
        .query((rs, n) -> {
          if (!rs.getString("request_hash").equals(hash)) throw keyReused();
          return "COMPLETED".equals(rs.getString("status")) ? (Object) json.readTree(rs.getString("body")) : null;
        }).optional();
  }

  private CatalogClient.Quote sellable(UUID variantId) {
    var quote = catalog.quote(List.of(variantId)).get(variantId);
    if (quote == null || !quote.found() || !quote.active())
      throw new Api.Problem(HttpStatus.CONFLICT, "CONFLICT", "VARIANT_NOT_AVAILABLE", List.of(new Api.FieldError("variant_id", "not available")));
    return quote;
  }
  private void requireStock(String sku, int quantity) {
    int available = stock.stock(sku).map(StockClient.Stock::available).orElse(0);
    if (available < quantity)
      throw new Api.Problem(HttpStatus.CONFLICT, "CONFLICT", "OUT_OF_STOCK", List.of(new Api.FieldError("quantity", "available=" + available)));
  }
  private static void requireVersion(long expected, long actual) {
    if (expected != actual)
      throw new Api.Problem(HttpStatus.CONFLICT, "CONFLICT", "VERSION_CONFLICT", List.of(new Api.FieldError("expected_version", "current=" + actual)));
  }
  private static Api.Problem limit(String message, String field, String detail) {
    return new Api.Problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, List.of(new Api.FieldError(field, detail)));
  }
  private static Api.Problem keyReused() {
    return new Api.Problem(HttpStatus.CONFLICT, "CONFLICT", "IDEMPOTENCY_KEY_REUSED", List.of());
  }
  static Api.Problem itemNotFound() {
    return new Api.Problem(HttpStatus.NOT_FOUND, "NOT_FOUND", "CART_ITEM_NOT_FOUND", List.of());
  }
  static String sha256(String value) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
    catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
  }
}
