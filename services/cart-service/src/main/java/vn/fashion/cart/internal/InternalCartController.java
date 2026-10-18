package vn.fashion.cart.internal;

import io.micrometer.tracing.Tracer;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.fashion.cart.cart.CartController;
import vn.fashion.cart.cart.CartItemsService;
import vn.fashion.cart.cart.CartRepository;
import vn.fashion.cart.cart.CartRequests;
import vn.fashion.cart.web.Api;

/** CART-03 internal API for order-service (03 section 4). Not routed by the Gateway. Auth before anything else. */
@RestController
@RequestMapping("/internal/api/v1/carts/{cartId}")
public class InternalCartController {
  public record Snapshot(UUID cartId, long version, String owner, List<Item> items) { }
  public record Item(UUID itemId, UUID variantId, String sku, int quantity, long itemVersion, long priceSnapshot, long catalogVersion) { }
  private final InternalCallers callers;
  private final CartRepository carts;
  private final CheckoutCleanupService cleanup;
  private final ObjectMapper json;
  private final Tracer tracer;

  public InternalCartController(InternalCallers callers, CartRepository carts, CheckoutCleanupService cleanup, ObjectMapper json, Tracer tracer) {
    this.callers = callers; this.carts = carts; this.cleanup = cleanup; this.json = json; this.tracer = tracer;
  }

  /** Read only: no catalog/inventory call, no sliding expiry, no write (spec section 3). */
  @GetMapping("/snapshot")
  public ResponseEntity<Api.Response<Snapshot>> snapshot(@PathVariable String cartId,
      @RequestHeader(name = "Authorization", required = false) String authorization,
      @RequestHeader(name = "Cart-Actor", required = false) String actorHeader,
      @RequestParam(name = "expected_version", required = false) String version) {
    callers.require(authorization);
    var actor = InternalCallers.actor(actorHeader);
    UUID id = CartController.parseId(cartId);
    long expected = CartRequests.version(version);
    var cart = carts.findOwned(id, actor).orElseThrow(CartRepository::notFound);
    CartItemsService.requireVersion(expected, cart.version());
    var items = carts.items(id).stream().map(i -> new Item(i.id(), i.variantId(), i.sku(), i.quantity(), i.version(),
        i.priceSnapshot(), i.catalogVersion())).toList();
    return Api.ok(HttpStatus.OK, new Snapshot(id, cart.version(), cart.userId() != null ? "MEMBER" : "GUEST", items), tracer);
  }

  /** Spec section 4: Idempotency-Key must be the order_id, so a retry under any other key cannot deduct again. */
  @PostMapping("/checkout-cleanup")
  public ResponseEntity<?> cleanup(@PathVariable String cartId,
      @RequestHeader(name = "Authorization", required = false) String authorization,
      @RequestHeader(name = "Cart-Actor", required = false) String actorHeader,
      @RequestHeader(name = "Idempotency-Key", required = false) String key,
      @RequestBody(required = false) String body) {
    callers.require(authorization);
    var actor = InternalCallers.actor(actorHeader);
    UUID id = CartController.parseId(cartId);
    if (key == null || key.isBlank()) throw keyProblem("IDEMPOTENCY_KEY_REQUIRED", "is required");
    var request = CartRequests.cleanup(object(body));
    if (!key.equals(request.orderId().toString())) throw keyProblem("IDEMPOTENCY_KEY_MISMATCH", "must equal order_id");
    return Api.ok(HttpStatus.OK, cleanup.cleanup(actor, id, request), tracer);
  }

  private JsonNode object(String body) {
    try {
      JsonNode node = body == null ? null : json.readTree(body);
      if (node != null && node.isObject()) return node;
    } catch (JacksonException ignored) { }
    throw new Api.Problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "MALFORMED_JSON", List.of());
  }
  private static Api.Problem keyProblem(String message, String detail) {
    return new Api.Problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, List.of(new Api.FieldError("Idempotency-Key", detail)));
  }
}
