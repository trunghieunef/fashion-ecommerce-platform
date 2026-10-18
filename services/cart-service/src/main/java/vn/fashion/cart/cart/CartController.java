package vn.fashion.cart.cart;

import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.fashion.cart.web.Api;

@RestController
@RequestMapping("/api/v1/carts")
public class CartController {
  private final CartIdentity identity;
  private final CartRepository carts;
  private final CartPreview preview;
  private final Tracer tracer;

  public CartController(CartIdentity identity, CartRepository carts, CartPreview preview, Tracer tracer) {
    this.identity = identity; this.carts = carts; this.preview = preview; this.tracer = tracer;
  }

  /** Member: the ACTIVE cart (201 when created). Guest: the cookie's cart, else a new cart and cookie (201). */
  @PostMapping
  public ResponseEntity<Api.Response<CartView>> open(HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response) {
    var actor = identity.resolve(request);
    if (actor.isPresent() && actor.get().member()) {
      boolean created = carts.createMember(actor.get().userId());
      return Api.ok(created ? HttpStatus.CREATED : HttpStatus.OK, view(carts.findActive(actor.get()).orElseThrow()), tracer);
    }
    if (actor.isPresent()) {
      var existing = carts.findActive(actor.get());
      if (existing.isPresent()) {
        carts.touch(existing.get().id());
        identity.refresh(request, response);
        return Api.ok(HttpStatus.OK, view(carts.findActive(actor.get()).orElseThrow()), tracer);
      }
    }
    String token = identity.newGuestToken();
    var cart = carts.createGuest(CartIdentity.hash(token));
    var created = Api.ok(HttpStatus.CREATED, view(cart), tracer);
    return ResponseEntity.status(created.getStatusCode()).headers(created.getHeaders())
        .header(HttpHeaders.SET_COOKIE, CartIdentity.cookie(token).toString()).body(created.getBody());
  }

  @GetMapping("/mine")
  public ResponseEntity<Api.Response<CartView>> mine(HttpServletRequest request) {
    var actor = identity.resolve(request).filter(CartActor::member).orElseThrow(CartIdentity::unauthorized);
    return Api.ok(HttpStatus.OK, view(carts.findActive(actor).orElseThrow(CartRepository::notFound)), tracer);
  }

  @GetMapping("/{cartId}")
  public ResponseEntity<Api.Response<CartView>> get(@PathVariable String cartId, HttpServletRequest request,
                                                    jakarta.servlet.http.HttpServletResponse response) {
    var actor = identity.resolve(request).orElseThrow(CartRepository::notFound);
    var cart = carts.findOwned(parseId(cartId), actor).orElseThrow(CartRepository::notFound);
    carts.touch(cart.id());
    var body = view(carts.findOwned(cart.id(), actor).orElseThrow(CartRepository::notFound));
    identity.refresh(request, response);
    return Api.ok(HttpStatus.OK, body, tracer);
  }

  /** Reads carry the labeled preview (spec section 6); it is computed outside any transaction and never stored. */
  CartView view(CartRepository.CartRow cart) {
    var items = carts.items(cart.id());
    var loaded = preview.load(items);
    return CartView.of(cart, items, loaded.byItem(), loaded.status());
  }

  /** Canonical lowercase UUID only; anything else is the same 404 as a missing cart. */
  public static UUID parseId(String raw) {
    try {
      UUID id = UUID.fromString(raw);
      if (!id.toString().equals(raw)) throw CartRepository.notFound();
      return id;
    } catch (IllegalArgumentException e) { throw CartRepository.notFound(); }
  }
}
