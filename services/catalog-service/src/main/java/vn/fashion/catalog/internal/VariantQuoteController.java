package vn.fashion.catalog.internal;

import io.micrometer.tracing.Tracer;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import vn.fashion.catalog.internal.VariantQuoteRepository.QuoteItem;
import vn.fashion.catalog.web.Api;
import vn.fashion.platform.security.AccessTokenVerifier;
import vn.fashion.platform.security.ServiceTokenVerifier;

@RestController
public class VariantQuoteController {
  static final String PATH = "/internal/api/v1/catalog/variants/quote";
  private static final Set<String> ALLOWED_CALLERS = Set.of("cart-service", "order-service");
  public record QuoteRequest(List<String> variantIds) { }
  private final ServiceTokenVerifier callers;
  private final VariantQuoteRepository quotes;
  private final Tracer tracer;

  public VariantQuoteController(@Value("${fashion.catalog.internal-callers}") String internalCallers,
                                VariantQuoteRepository quotes, Tracer tracer) {
    this.callers = new ServiceTokenVerifier("catalog-service", internalCallers.isBlank() ? Map.of()
        : AccessTokenVerifier.parseKeys(internalCallers), Clock.systemUTC());
    this.quotes = quotes;
    this.tracer = tracer;
  }

  @PostMapping(PATH)
  public ResponseEntity<Api.Response<List<QuoteItem>>> quote(
      @RequestHeader(name = "Authorization", required = false) String authorization,
      @RequestBody QuoteRequest request) {
    String token = authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)
        ? authorization.substring(7).strip() : null;
    String caller = callers.verify(token).orElseThrow(() ->
        new Api.Problem(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "INVALID_SERVICE_TOKEN", List.of()));
    if (!ALLOWED_CALLERS.contains(caller)) {
      throw new Api.Problem(HttpStatus.FORBIDDEN, "FORBIDDEN", "CALLER_NOT_ALLOWED", List.of());
    }
    var ids = new ArrayList<UUID>();
    var unique = new HashSet<UUID>();
    if (request.variantIds() == null || request.variantIds().isEmpty() || request.variantIds().size() > 100) {
      throw invalidIds();
    }
    for (String value : request.variantIds()) {
      try {
        if (value == null) throw invalidIds();
        UUID id = UUID.fromString(value);
        if (!id.toString().equalsIgnoreCase(value) || !unique.add(id)) throw invalidIds();
        ids.add(id);
      } catch (IllegalArgumentException e) { throw invalidIds(); }
    }
    return Api.ok(HttpStatus.OK, quotes.quote(ids), tracer);
  }

  private static Api.Problem invalidIds() {
    return new Api.Problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "INVALID_FIELDS",
        List.of(new Api.FieldError("variant_ids", "must contain 1..100 distinct UUIDs")));
  }
}
