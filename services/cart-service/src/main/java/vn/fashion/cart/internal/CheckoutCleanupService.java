package vn.fashion.cart.internal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import vn.fashion.cart.cart.CartActor;
import vn.fashion.cart.cart.CartItemsService;
import vn.fashion.cart.cart.CartRepository;
import vn.fashion.cart.cart.CartRequests;
import vn.fashion.cart.web.Api;
import vn.fashion.platform.idempotency.IdempotencyStore;

/**
 * CART-03 spec section 4 (T18): one transaction, no HTTP. Idempotent per order_id; removes only items whose version and
 * quantity still match the order snapshot, so edits and additions made after checkout survive.
 */
@Service
public class CheckoutCleanupService {
  public record Retained(UUID itemId, String reason, int currentQuantity) { }
  public record Result(UUID cartId, long cartVersion, UUID orderId, List<UUID> removed, List<Retained> retained, List<UUID> missing) { }
  static final String ACTOR = "service:order-service";
  private final JdbcClient jdbc;
  private final TransactionTemplate tx;
  private final CartRepository carts;
  private final IdempotencyStore keys;
  private final ObjectMapper json;

  public CheckoutCleanupService(JdbcClient jdbc, TransactionTemplate tx, CartRepository carts, IdempotencyStore keys, ObjectMapper json) {
    this.jdbc = jdbc; this.tx = tx; this.carts = carts; this.keys = keys; this.json = json;
  }

  /** Returns a fresh Result or the stored response of the earlier cleanup of the same order. */
  public Object cleanup(CartActor actor, UUID cartId, CartRequests.Cleanup request) {
    String operation = "cart.checkout-cleanup:" + cartId;
    String key = request.orderId().toString();
    String hash = CartItemsService.sha256(actor.key() + "|" + request.items().stream()
        .sorted(Comparator.comparing(CartRequests.CleanupItem::itemId))
        .map(i -> i.itemId() + ":" + i.itemVersion() + ":" + i.quantity()).collect(Collectors.joining(",")));
    return tx.execute(status -> {
      // A concurrent duplicate waits here on the primary key, then reads the committed outcome.
      var outcome = keys.begin(ACTOR, operation, key, hash);
      if (outcome.status() == IdempotencyStore.Status.CONFLICT)
        throw new Api.Problem(HttpStatus.CONFLICT, "CONFLICT", "IDEMPOTENCY_KEY_REUSED", List.of());
      // Replay skips lockOwned: the hash covers actor.key(), so another actor gets 409 above, never this body.
      if (outcome.status() == IdempotencyStore.Status.COMPLETED) return (Object) json.readTree(outcome.responseBody());
      // begin and finish share this transaction, so a PROCESSING row is never visible; fail loudly if that changes.
      if (outcome.status() == IdempotencyStore.Status.IN_PROGRESS) throw new IllegalStateException("cleanup idempotency row left PROCESSING");
      var cart = carts.lockOwned(cartId, actor);
      var current = carts.items(cartId).stream().collect(Collectors.toMap(CartRepository.CartItemRow::id, Function.identity()));
      var removed = new ArrayList<UUID>();
      var retained = new ArrayList<Retained>();
      var missing = new ArrayList<UUID>();
      for (var item : request.items()) {
        var now = current.get(item.itemId());
        if (now == null) missing.add(item.itemId());
        else if (now.version() == item.itemVersion() && now.quantity() == item.quantity()) removed.add(item.itemId());
        else retained.add(new Retained(item.itemId(), "ITEM_CHANGED", now.quantity()));
      }
      long version = cart.version();
      if (!removed.isEmpty()) {
        jdbc.sql("delete from cart_items where cart_id = :cart and id in (:ids)").param("cart", cartId).param("ids", removed).update();
        jdbc.sql("update carts set version = version + 1, updated_at = now() where id = :id").param("id", cartId).update();
        version++;
      }
      var result = new Result(cartId, version, request.orderId(), removed, retained, missing);
      keys.finish(ACTOR, operation, key, cartId, 200, json.writeValueAsString(result));
      return result;
    });
  }
}
