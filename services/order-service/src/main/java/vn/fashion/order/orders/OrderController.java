package vn.fashion.order.orders;

import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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
  private final OrderIdentity identity;
  private final QuoteService quotes;
  private final QuoteTokens tokens;
  private final ObjectMapper json;
  private final Tracer tracer;

  public OrderController(OrderIdentity identity, QuoteService quotes, QuoteTokens tokens, ObjectMapper json, Tracer tracer) {
    this.identity = identity; this.quotes = quotes; this.tokens = tokens; this.json = json; this.tracer = tracer;
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
