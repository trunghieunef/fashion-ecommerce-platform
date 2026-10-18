package vn.fashion.cart.cart;

import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.fashion.cart.web.Api;

/** Item mutations (spec section 5). Ownership is resolved before the body is read; misses are 404. */
@RestController
@RequestMapping("/api/v1/carts/{cartId}")
public class CartItemsController {
  private final CartIdentity identity;
  private final CartItemsService items;
  private final ObjectMapper json;
  private final Tracer tracer;

  public CartItemsController(CartIdentity identity, CartItemsService items, ObjectMapper json, Tracer tracer) {
    this.identity = identity; this.items = items; this.json = json; this.tracer = tracer;
  }

  @PostMapping("/items")
  public ResponseEntity<?> add(@PathVariable String cartId, @RequestHeader(name = "Idempotency-Key", required = false) String key,
                               HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response) {
    var actor = actor(request); UUID cart = CartController.parseId(cartId);
    return ok(request, response, items.add(actor, cart, key, CartRequests.add(body(request))));
  }

  @PutMapping("/items/{itemId}")
  public ResponseEntity<?> put(@PathVariable String cartId, @PathVariable String itemId, HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response) {
    var actor = actor(request); UUID cart = CartController.parseId(cartId);
    return ok(request, response, items.put(actor, cart, item(itemId), CartRequests.put(body(request))));
  }

  @DeleteMapping("/items/{itemId}")
  public ResponseEntity<?> remove(@PathVariable String cartId, @PathVariable String itemId,
                                  @RequestParam(name = "expected_version", required = false) String version, HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response) {
    var actor = actor(request); UUID cart = CartController.parseId(cartId);
    return ok(request, response, items.remove(actor, cart, item(itemId), CartRequests.version(version)));
  }

  @DeleteMapping
  public ResponseEntity<?> clear(@PathVariable String cartId, @RequestParam(name = "expected_version", required = false) String version,
                                 HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response) {
    var actor = actor(request); UUID cart = CartController.parseId(cartId);
    return ok(request, response, items.clear(actor, cart, CartRequests.version(version)));
  }

  /** Success only: guest cookie re-issued (sliding 30 days); errors leave the browser cookie untouched. */
  private ResponseEntity<?> ok(HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response, Object body) {
    identity.refresh(request, response);
    return Api.ok(HttpStatus.OK, body, tracer);
  }
  private CartActor actor(HttpServletRequest request) { return identity.resolve(request).orElseThrow(CartRepository::notFound); }
  private static UUID item(String raw) {
    try {
      UUID id = UUID.fromString(raw);
      if (id.toString().equals(raw)) return id;
    } catch (IllegalArgumentException ignored) { }
    throw CartItemsService.itemNotFound();
  }
  private JsonNode body(HttpServletRequest request) {
    try {
      JsonNode node = json.readTree(request.getInputStream());
      if (node == null || !node.isObject()) throw malformed();
      return node;
    } catch (IOException | JacksonException e) { throw malformed(); }
  }
  private static Api.Problem malformed() {
    return new Api.Problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "MALFORMED_JSON", List.of());
  }
}
