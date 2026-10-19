package vn.fashion.shipping.deps;

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

/** Catalog internal quote, read only for sellability and weight_grams (SHP-01 spec section 5). */
@Component
public class CatalogClient {
  /** weightGrams is 0 when not found. */
  public record Variant(UUID variantId, boolean found, boolean active, int weightGrams) { }
  private final HttpClient http;
  private final ServiceTokens tokens;
  private final ObjectMapper json;
  private final String url;
  private final Duration timeout;

  public CatalogClient(ServiceTokens tokens, ObjectMapper json, @Value("${fashion.shipping.catalog-url}") String url,
                       @Value("${fashion.shipping.dependency-timeout:1s}") Duration timeout) {
    this.tokens = tokens; this.json = json; this.url = url; this.timeout = timeout;
    this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
  }

  /** 1..100 distinct ids; every id comes back (found=false when unknown); a malformed answer is DependencyUnavailable. */
  public Map<UUID, Variant> variants(List<UUID> ids) {
    try {
      var body = json.writeValueAsString(Map.of("variant_ids", ids.stream().map(UUID::toString).toList()));
      var request = HttpRequest.newBuilder(URI.create(url + "/internal/api/v1/catalog/variants/quote")).timeout(timeout)
          .header("Authorization", tokens.bearer("catalog-service")).header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body)).build();
      var response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) throw new DependencyUnavailable("catalog");
      JsonNode data = json.readTree(response.body()).path("data");
      if (!data.isArray()) throw new DependencyUnavailable("catalog");
      var result = new HashMap<UUID, Variant>();
      for (JsonNode n : data) {
        UUID id = UUID.fromString(n.path("variant_id").asString());
        if (!n.path("found").asBoolean(false)) { result.put(id, new Variant(id, false, false, 0)); continue; }
        var weight = n.path("weight_grams");
        if (!n.path("active").isBoolean() || !weight.isIntegralNumber() || !weight.canConvertToInt() || weight.intValue() < 1)
          throw new DependencyUnavailable("catalog");
        result.put(id, new Variant(id, true, n.path("active").asBoolean(), weight.intValue()));
      }
      if (!result.keySet().containsAll(ids)) throw new DependencyUnavailable("catalog");
      return result;
    } catch (DependencyUnavailable e) { throw e; }
    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new DependencyUnavailable("catalog"); }
    catch (Exception e) { throw new DependencyUnavailable("catalog"); }
  }
}
