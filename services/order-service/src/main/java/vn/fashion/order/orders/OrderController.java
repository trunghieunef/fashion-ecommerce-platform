package vn.fashion.order.orders;

import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;
import vn.fashion.order.quotes.Quote;
import vn.fashion.order.quotes.QuoteRequest;
import vn.fashion.order.quotes.QuoteService;
import vn.fashion.order.quotes.QuoteTokens;
import vn.fashion.order.web.Api;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {
  public record QuoteView(String quoteToken, Instant expiresAt, UUID cartId, long cartVersion, String method,
                          Quote.Address shippingAddress, List<Item> items, Quote.Totals totals) { }
  public record Item(UUID variantId, String sku, String name, String size, String color, String imageUrl, int quantity,
                     long unitPrice, long lineTotal) { }
  /** Spec section 7: guest order credential, 90 days (O06), scoped to its own order path. */
  static final Duration ACCESS_TTL = Duration.ofDays(90);
  static final String ACCESS_COOKIE = "order_access";
  private final OrderIdentity identity;
  private final QuoteService quotes;
  private final QuoteTokens tokens;
  private final OrderService orders;
  private final ObjectMapper json;
  private final Tracer tracer;

  public OrderController(OrderIdentity identity, QuoteService quotes, QuoteTokens tokens, OrderService orders, ObjectMapper json,
                         Tracer tracer) {
    this.identity = identity; this.quotes = quotes; this.tokens = tokens; this.orders = orders; this.json = json; this.tracer = tracer;
  }

  /** 202 + Location/Retry-After while PROCESSING (always in ORD-01); a retry with the same key gets the same order. */
  @PostMapping
  public ResponseEntity<Api.Response<OrderView>> create(@RequestHeader(name = "Idempotency-Key", required = false) String key,
                                                       @RequestBody(required = false) String body, HttpServletRequest request) {
    var actor = identity.require(request);
    Api.requireKey(key);
    var meta = Api.metadata(tracer);
    var created = orders.create(actor, key, OrderService.parse(Api.object(json, body)), meta.traceId());
    var view = created.view();
    var response = Api.ok(view.processing() ? HttpStatus.ACCEPTED : HttpStatus.OK, view, meta);
    var builder = ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders());
    if (view.processing())
      builder.header(HttpHeaders.LOCATION, view.statusUrl()).header(HttpHeaders.RETRY_AFTER, String.valueOf(view.retryAfterSeconds()));
    if (created.guestAccessToken() != null) builder.header(HttpHeaders.SET_COOKIE, accessCookie(created.guestAccessToken(), view.orderNo()));
    return builder.body(response.getBody());
  }

  /** 409 CART_ALREADY_ORDERED keeps its error body; the same guest also gets a credential for that order (PR review I2). */
  @ExceptionHandler(OrderService.AlreadyOrdered.class)
  ResponseEntity<Api.Error> alreadyOrdered(OrderService.AlreadyOrdered e) {
    var error = Api.error(e.status(), e.code(), e.getMessage(), e.errors(), tracer);
    var builder = ResponseEntity.status(error.getStatusCode()).headers(error.getHeaders()).contentType(MediaType.APPLICATION_JSON);
    if (e.access() != null) builder.header(HttpHeaders.SET_COOKIE, accessCookie(e.access(), e.orderNo()));
    return builder.body(error.getBody());
  }

  static String accessCookie(String token, String orderNo) {
    return ResponseCookie.from(ACCESS_COOKIE, token).httpOnly(true).secure(true).sameSite("Lax").path("/api/v1/orders/" + orderNo)
        .maxAge(ACCESS_TTL).build().toString();
  }

  @GetMapping("/mine")
  public ResponseEntity<Api.Response<OrderService.Page>> mine(@RequestParam(required = false) String status,
      @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit, HttpServletRequest request) {
    var actor = identity.member(request).orElseThrow(OrderIdentity::unauthorized);
    return Api.ok(HttpStatus.OK, orders.mine(actor.userId(), status, cursor, limit), tracer);
  }

  /** Member by Bearer, guest by the order_access cookie of this order; never by order_no alone (03 section 2.3). */
  @GetMapping("/{orderNo}")
  public ResponseEntity<Api.Response<OrderView>> get(@PathVariable String orderNo, HttpServletRequest request) {
    var member = identity.member(request);
    String access = null;
    if (member.isEmpty() && request.getCookies() != null)
      for (var cookie : request.getCookies()) if (ACCESS_COOKIE.equals(cookie.getName())) access = cookie.getValue();
    return Api.ok(HttpStatus.OK, orders.readable(orderNo, member.map(OrderActor::userId).orElse(null), access), tracer);
  }

  @PostMapping("/quote")
  public ResponseEntity<Api.Response<QuoteView>> quote(@RequestBody(required = false) String body, HttpServletRequest request) {
    var actor = identity.require(request);
    var quote = quotes.quote(actor, QuoteRequest.parse(Api.object(json, body), !actor.member()), locale(request));
    return Api.ok(HttpStatus.OK, new QuoteView(tokens.sign(quote), quote.expiresAt(), quote.cartId(), quote.cartVersion(),
        quote.method(), quote.shippingAddress(), items(quote.items()), quote.totals()), tracer);
  }

  static List<Item> items(List<Quote.Line> lines) {
    return lines.stream().map(l -> new Item(l.variantId(), l.sku(), l.name(), l.size(), l.color(), l.imageUrl(), l.quantity(),
        l.unitPrice(), l.lineTotal())).toList();
  }
  static String locale(HttpServletRequest request) {
    String language = request.getHeader("Accept-Language");
    return language != null && language.strip().toLowerCase().startsWith("en") ? "en" : "vi";
  }
}
