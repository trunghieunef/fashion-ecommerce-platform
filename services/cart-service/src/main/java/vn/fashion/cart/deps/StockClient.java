package vn.fashion.cart.deps;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** INV-01 internal stock read: reference availability only, never a reservation. */
@Component
public class StockClient {
  public record Stock(int available, Instant observedAt) { }
  private final HttpClient http;
  private final ServiceTokens tokens;
  private final ObjectMapper json;
  private final String url;
  private final Duration timeout;

  public StockClient(ServiceTokens tokens, ObjectMapper json, @Value("${fashion.cart.inventory-url}") String url,
                     @Value("${fashion.cart.dependency-timeout:1s}") Duration timeout) {
    this.tokens = tokens; this.json = json; this.url = url; this.timeout = timeout;
    this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
  }

  /** Empty when inventory does not know the SKU yet (404 SKU_NOT_FOUND). */
  public Optional<Stock> stock(String sku) {
    try {
      var request = HttpRequest.newBuilder(URI.create(url + "/internal/api/v1/inventory/skus/"
              + URLEncoder.encode(sku, StandardCharsets.UTF_8).replace("+", "%20"))).timeout(timeout)
          .header("Authorization", tokens.bearer("inventory-service")).GET().build();
      var response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 404) return Optional.empty();
      if (response.statusCode() != 200) throw new DependencyUnavailable("inventory");
      JsonNode data = json.readTree(response.body()).path("data");
      if (!data.path("available").isIntegralNumber() || !data.path("observed_at").isString()) throw new DependencyUnavailable("inventory");
      return Optional.of(new Stock(data.path("available").intValue(), Instant.parse(data.path("observed_at").asString())));
    } catch (DependencyUnavailable e) { throw e; }
    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new DependencyUnavailable("inventory"); }
    catch (Exception e) { throw new DependencyUnavailable("inventory"); }
  }
}
