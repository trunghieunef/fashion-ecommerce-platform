package vn.fashion.cart.deps;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** CAT-02a internal quote (03 section 7): current sellability, effective price and product version. */
@Component
public class CatalogClient {
  public record Quote(UUID variantId, boolean found, boolean active, String sku, long effectivePrice, long productVersion) { }
  private final HttpClient http;
  private final ServiceTokens tokens;
  private final ObjectMapper json;
  private final String url;
  private final Duration timeout;

  public CatalogClient(ServiceTokens tokens, ObjectMapper json, @Value("${fashion.cart.catalog-url}") String url,
                       @Value("${fashion.cart.dependency-timeout:1s}") Duration timeout) {
    this.tokens = tokens; this.json = json; this.url = url; this.timeout = timeout;
    this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
  }

  /** 1..100 distinct ids; every returned id is in the map (found=false when catalog does not know it). */
  public Map<UUID, Quote> quote(List<UUID> variantIds) {
    try {
      var body = json.writeValueAsString(Map.of("variant_ids", variantIds.stream().map(UUID::toString).toList()));
      var request = HttpRequest.newBuilder(URI.create(url + "/internal/api/v1/catalog/variants/quote")).timeout(timeout)
          .header("Authorization", tokens.bearer("catalog-service")).header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body)).build();
      var response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) throw new DependencyUnavailable("catalog");
      JsonNode data = json.readTree(response.body()).path("data");
      if (!data.isArray()) throw new DependencyUnavailable("catalog");
      var result = new HashMap<UUID, Quote>();
      for (JsonNode n : data) {
        UUID id = UUID.fromString(n.path("variant_id").asString());
        if (!n.path("found").asBoolean(false)) { result.put(id, new Quote(id, false, false, null, 0, 0)); continue; }
        // Review I4: anything the cart could not store or would misread is a dependency failure, not a 409/500.
        var sku = n.path("sku");
        if (!n.path("active").isBoolean() || !n.path("effective_price").isIntegralNumber() || !n.path("effective_price").canConvertToLong()
            || n.path("effective_price").longValue() < 0 || !n.path("product_version").isIntegralNumber()
            || !sku.isString() || sku.asString().isEmpty() || sku.asString().length() > 64)
          throw new DependencyUnavailable("catalog");
        result.put(id, new Quote(id, true, n.path("active").asBoolean(false), n.path("sku").asString(),
            n.path("effective_price").longValue(), n.path("product_version").longValue()));
      }
      if (!result.keySet().containsAll(variantIds)) throw new DependencyUnavailable("catalog");
      return result;
    } catch (DependencyUnavailable e) { throw e; }
    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new DependencyUnavailable("catalog"); }
    catch (Exception e) { throw new DependencyUnavailable("catalog"); }
  }
}
