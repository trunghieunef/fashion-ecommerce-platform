package vn.fashion.cart.cart;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
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
 * CART-02 spec section 3 (T19): catalog/stock are read before the transaction; inside it both carts are locked in id order
 * and a guest cart already MERGED into this member cart is reported, never added again.
 */
@Service
public class CartMergeService {
  public record Adjustment(UUID variantId, String sku, String reason, int requested, int applied) { }
  public record Result(CartView cart, UUID mergedFrom, boolean alreadyMerged, List<Adjustment> adjustments) { }
  private final JdbcClient jdbc;
  private final TransactionTemplate tx;
  private final CartRepository carts;
  private final CartPreview preview;
  private final IdempotencyStore keys;
  private final ObjectMapper json;

  public CartMergeService(JdbcClient jdbc, TransactionTemplate tx, CartRepository carts, CartPreview preview,
                          IdempotencyStore keys, ObjectMapper json) {
    this.jdbc = jdbc; this.tx = tx; this.carts = carts; this.preview = preview; this.keys = keys; this.json = json;
  }

  /** Returns a fresh Result or the stored response of an earlier identical request. */
  public Object merge(UUID userId, String guestHash, String key, CartRequests.Merge r) {
    CartRequests.requireKey(key);
    var member = CartActor.member(userId);
    String operation = "cart.merge:" + r.guestCartId();
    String hash = CartItemsService.sha256(r.guestCartId() + "|" + r.guestExpectedVersion() + "|" + r.memberExpectedVersion());
    var guest = carts.findGuestForMerge(r.guestCartId(), guestHash).orElseThrow(CartRepository::notFound);
    // Outside any transaction (spec section 3): reference prices/stock for the guest lines; failure is 503, nothing written.
    // Parallel under one deadline (PR #27 review P2): sequential stock calls ran past the Gateway 2 s timeout.
    var quotes = Map.<UUID, CatalogClient.Quote>of();
    var available = new HashMap<String, Integer>();
    if ("ACTIVE".equals(guest.status())) {
      var items = carts.items(guest.id());
      if (!items.isEmpty()) {
        var fetched = preview.fetch(items);
        quotes = fetched.quotes();
        fetched.stock().forEach((sku, s) -> available.put(sku, s.map(StockClient.Stock::available).orElse(0)));
      }
    }
    carts.createMember(userId);
    var quoted = quotes;
    return tx.execute(status -> {
      var outcome = keys.begin(member.key(), operation, key, hash);
      if (outcome.status() == IdempotencyStore.Status.CONFLICT)
        throw new Api.Problem(HttpStatus.CONFLICT, "CONFLICT", "IDEMPOTENCY_KEY_REUSED", List.of());
      if (outcome.status() == IdempotencyStore.Status.COMPLETED) return (Object) json.readTree(outcome.responseBody());
      var target = carts.findActive(member).orElseThrow(CartRepository::notFound);
      carts.lockInOrder(target.id(), guest.id());
      var lockedGuest = carts.findGuestForMerge(guest.id(), guestHash).orElseThrow(CartRepository::notFound);
      var lockedTarget = carts.findActive(member).orElseThrow(CartRepository::notFound);
      // The locked row must still be the member's ACTIVE cart (review M3: checkout may move it out of ACTIVE later).
      if (!lockedTarget.id().equals(target.id())) version("member_expected_version", -1, lockedTarget.version());
      Result result;
      if ("MERGED".equals(lockedGuest.status())) {
        if (!carts.mergedInto(lockedGuest.id()).filter(lockedTarget.id()::equals).isPresent()) throw CartRepository.notFound();
        result = new Result(view(lockedTarget), lockedGuest.id(), true, List.of());
      } else {
        version("guest_expected_version", r.guestExpectedVersion(), lockedGuest.version());
        // Guest edited between the quote and the lock: its new lines have no quote (review M2) -> client retries.
        if (lockedGuest.version() != guest.version()) version("guest_expected_version", guest.version(), lockedGuest.version());
        version("member_expected_version", r.memberExpectedVersion(), lockedTarget.version());
        var adjustments = fold(lockedGuest.id(), lockedTarget.id(), quoted, available);
        jdbc.sql("update carts set status = 'MERGED', merged_into = :into, version = version + 1, updated_at = now() where id = :id")
            .param("into", lockedTarget.id()).param("id", lockedGuest.id()).update();
        jdbc.sql("update carts set version = version + 1, updated_at = now() where id = :id").param("id", lockedTarget.id()).update();
        result = new Result(view(carts.findActive(member).orElseThrow()), lockedGuest.id(), false, adjustments);
      }
      keys.finish(member.key(), operation, key, lockedTarget.id(), 200, json.writeValueAsString(result));
      return result;
    });
  }

  /** Adds each guest line once: target = max(member qty, min(member + guest, 99, observed available)); every cut is reported. */
  private List<Adjustment> fold(UUID guestId, UUID targetId, Map<UUID, CatalogClient.Quote> quotes, Map<String, Integer> available) {
    var memberItems = carts.items(targetId).stream().collect(Collectors.toMap(CartRepository.CartItemRow::variantId, Function.identity()));
    int lines = memberItems.size();
    var adjustments = new ArrayList<Adjustment>();
    for (var g : carts.items(guestId)) {
      var q = quotes.get(g.variantId());
      if (q == null || !q.found() || !q.active()) { adjustments.add(new Adjustment(g.variantId(), g.sku(), "UNAVAILABLE", g.quantity(), 0)); continue; }
      var existing = memberItems.get(g.variantId());
      int had = existing == null ? 0 : existing.quantity();
      int wanted = had + g.quantity();
      int target = Math.max(had, Math.min(Math.min(wanted, CartItemsService.MAX_QUANTITY), available.getOrDefault(g.sku(), 0)));
      int applied = target - had;
      if (existing == null) {
        if (target == 0) { adjustments.add(new Adjustment(g.variantId(), q.sku(), "OUT_OF_STOCK", g.quantity(), 0)); continue; }
        if (lines >= CartItemsService.MAX_ITEMS) { adjustments.add(new Adjustment(g.variantId(), q.sku(), "CART_ITEM_LIMIT", g.quantity(), 0)); continue; }
        jdbc.sql("""
            insert into cart_items(id, cart_id, variant_id, sku, quantity, price_snapshot, catalog_version, created_at)
            values (:id, :cart, :variant, :sku, :q, :p, :v, :created)""")
            .param("id", UUID.randomUUID()).param("cart", targetId).param("variant", g.variantId()).param("sku", q.sku())
            .param("q", target).param("p", q.effectivePrice()).param("v", q.productVersion()).param("created", Timestamp.from(g.createdAt())).update();
        lines++;
      } else if (applied > 0) {
        jdbc.sql("""
            update cart_items set quantity = :q, price_snapshot = :p, catalog_version = :v, version = version + 1, updated_at = now(),
              created_at = least(created_at, :created) where id = :id""")
            .param("q", target).param("p", q.effectivePrice()).param("v", q.productVersion())
            .param("created", Timestamp.from(g.createdAt())).param("id", existing.id()).update();
      } else {
        // Nothing added (capped), yet the line keeps the older created_at (PR #27 review P3, spec section 3.5).
        jdbc.sql("update cart_items set created_at = least(created_at, :created) where id = :id")
            .param("created", Timestamp.from(g.createdAt())).param("id", existing.id()).update();
      }
      if (applied < g.quantity()) adjustments.add(new Adjustment(g.variantId(), q.sku(), "QUANTITY_CAPPED", g.quantity(), applied));
    }
    return adjustments;
  }

  private CartView view(CartRepository.CartRow cart) { return CartView.of(cart, carts.items(cart.id()), Map.of(), "NOT_LOADED"); }
  private static void version(String field, long expected, long actual) {
    if (expected != actual)
      throw new Api.Problem(HttpStatus.CONFLICT, "VERSION_CONFLICT", "VERSION_CONFLICT", List.of(new Api.FieldError(field, "current=" + actual)));
  }
}
