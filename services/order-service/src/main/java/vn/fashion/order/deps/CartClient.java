package vn.fashion.order.deps;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import vn.fashion.order.web.Api;

/** CART-03 internal snapshot, read as the actor order verified (Cart-Actor). */
@Component
public class CartClient {
  public record Item(UUID itemId, UUID variantId, String sku, int quantity, long itemVersion) { }
  public record Snapshot(UUID cartId, long version, List<Item> items) { }
  private final JsonHttp http;
  private final String url;

  public CartClient(JsonHttp http, @Value("${fashion.order.cart-url}") String url) { this.http = http; this.url = url; }

  /** ORD-09 cleanup of the ordered lines, keyed by order_id (CART-03); retained/missing items are a normal result. */
  public Outcome cleanup(vn.fashion.order.saga.SagaOrder o) {
    var body = java.util.Map.of("order_id", o.id().toString(), "items", o.items().stream()
        .map(i -> java.util.Map.of("item_id", i.cartItemId().toString(), "item_version", i.cartItemVersion(), "quantity", i.quantity()))
        .toList());
    return Outcome.of("cart", http, "cart-service", url + "/internal/api/v1/carts/" + o.cartId() + "/checkout-cleanup", body,
        "Idempotency-Key", o.id().toString(), "Cart-Actor", o.actorKey());
  }

  /** 404 CART_NOT_FOUND and 409 VERSION_CONFLICT pass through; anything else unexpected is 503. */
  public Snapshot snapshot(UUID cartId, long expectedVersion, String actorKey) {
    var reply = http.call("cart", "cart-service",
        url + "/internal/api/v1/carts/" + cartId + "/snapshot?expected_version=" + expectedVersion, null, "Cart-Actor", actorKey);
    if (reply.status() == 404) throw new Api.Problem(HttpStatus.NOT_FOUND, "NOT_FOUND", "CART_NOT_FOUND", List.of());
    if (reply.status() == 409 && reply.message().equals("VERSION_CONFLICT"))
      throw new Api.Problem(HttpStatus.CONFLICT, "VERSION_CONFLICT", "VERSION_CONFLICT",
          List.of(new Api.FieldError("cart_version", "the cart changed; quote again")));
    if (reply.status() != 200) throw new DependencyUnavailable("cart");
    try {
      JsonNode d = reply.data();
      var items = new ArrayList<Item>();
      for (JsonNode n : d.path("items"))
        items.add(new Item(UUID.fromString(n.path("item_id").asString()), UUID.fromString(n.path("variant_id").asString()),
            n.path("sku").asString(), n.path("quantity").asInt(), n.path("item_version").asLong()));
      if (!UUID.fromString(d.path("cart_id").asString()).equals(cartId) || d.path("version").asLong() != expectedVersion)
        throw new DependencyUnavailable("cart");
      return new Snapshot(cartId, expectedVersion, items);
    } catch (DependencyUnavailable e) { throw e; }
    catch (RuntimeException e) { throw new DependencyUnavailable("cart"); }
  }
}
