package vn.fashion.catalog.internal;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.fashion.catalog.internal.VariantQuoteRepository.QuoteItem;
import vn.fashion.catalog.web.Api;
import vn.fashion.platform.security.AccessTokenVerifier;
import vn.fashion.platform.security.ServiceTokenVerifier;

@RestController
public class VariantQuoteController {
  static final String PATH = "/internal/api/v1/catalog/variants/quote";
  private static final Set<String> ALLOWED_CALLERS = Set.of("cart-service", "order-service");
  private static final List<MediaType> JSON_TYPES = List.of(MediaType.APPLICATION_JSON,
      MediaType.parseMediaType("application/*+json"));
  public static class QuoteRequest {
    public List<String> variantIds;
    @JsonIgnore public final List<String> unknownFields = new ArrayList<>();
    @JsonAnySetter public void unknown(String key, JsonNode value) { unknownFields.add(key); }
  }
  private final ServiceTokenVerifier callers;
  private final VariantQuoteRepository quotes;
  private final Tracer tracer;
  private final ObjectMapper json;

  public VariantQuoteController(@Value("${fashion.catalog.internal-callers}") String internalCallers,
                                VariantQuoteRepository quotes, Tracer tracer, ObjectMapper json) {
    this.callers = new ServiceTokenVerifier("catalog-service", internalCallers.isBlank() ? Map.of()
        : AccessTokenVerifier.parseKeys(internalCallers), Clock.systemUTC());
    this.quotes = quotes;
    this.tracer = tracer;
    this.json = json;
  }

  @PostMapping(PATH)
  public ResponseEntity<Api.Response<List<QuoteItem>>> quote(
      @RequestHeader(name = "Authorization", required = false) String authorization,
      HttpServletRequest body) throws HttpMediaTypeNotSupportedException {
    String token = authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)
        ? authorization.substring(7).strip() : null;
    String caller = callers.verify(token).orElseThrow(() ->
        new Api.Problem(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "INVALID_SERVICE_TOKEN", List.of()));
    if (!ALLOWED_CALLERS.contains(caller)) {
      throw new Api.Problem(HttpStatus.FORBIDDEN, "FORBIDDEN", "CALLER_NOT_ALLOWED", List.of());
    }
    try {
      MediaType type = MediaType.parseMediaType(body.getContentType() == null
          ? MediaType.APPLICATION_OCTET_STREAM_VALUE : body.getContentType());
      if (JSON_TYPES.stream().noneMatch(supported -> supported.includes(type))) {
        throw new HttpMediaTypeNotSupportedException("Unsupported media type", JSON_TYPES);
      }
    } catch (InvalidMediaTypeException e) {
      throw new HttpMediaTypeNotSupportedException("Unsupported media type", JSON_TYPES);
    }
    QuoteRequest request;
    try {
      request = json.readValue(body.getInputStream(), QuoteRequest.class);
    } catch (IOException | JacksonException e) {
      throw malformedJson();
    }
    if (request == null) throw malformedJson();
    if (!request.unknownFields.isEmpty()) {
      throw new Api.Problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "INVALID_FIELDS",
          request.unknownFields.stream().map(key -> new Api.FieldError(key, "unknown field")).toList());
    }
    var ids = new ArrayList<UUID>();
    var unique = new HashSet<UUID>();
    if (request.variantIds == null || request.variantIds.isEmpty() || request.variantIds.size() > 100) {
      throw invalidIds();
    }
    for (String value : request.variantIds) {
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

  private static Api.Problem malformedJson() {
    return new Api.Problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "MALFORMED_JSON", List.of());
  }
}
