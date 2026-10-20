package vn.fashion.order.deps;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** CAT-02a internal variant quote: the price authority for the order. */
@Component
public class CatalogClient {
  public record Variant(boolean found, boolean active, String sku, String nameVi, String nameEn, String size, String color,
                        long effectivePrice, long productVersion, String imageAssetId) { }
  private final JsonHttp http;
  private final String url;

  public CatalogClient(JsonHttp http, @Value("${fashion.order.catalog-url}") String url) { this.http = http; this.url = url; }

  /** Every requested id is in the result (found=false when catalog does not know it); a malformed answer is 503. */
  public Map<UUID, Variant> quote(List<UUID> variantIds) {
    var reply = http.call("catalog", "catalog-service", url + "/internal/api/v1/catalog/variants/quote",
        Map.of("variant_ids", variantIds.stream().map(UUID::toString).toList()));
    if (reply.status() != 200 || !reply.data().isArray()) throw new DependencyUnavailable("catalog");
    try {
      var result = new HashMap<UUID, Variant>();
      for (JsonNode n : reply.data()) {
        UUID id = UUID.fromString(n.path("variant_id").asString());
        if (!n.path("found").asBoolean(false)) {
          result.put(id, new Variant(false, false, null, null, null, null, null, 0, 0, null));
          continue;
        }
        JsonNode price = n.path("effective_price");
        if (!price.isIntegralNumber() || !price.canConvertToLong() || price.longValue() < 0 || !n.path("active").isBoolean())
          throw new DependencyUnavailable("catalog");
        result.put(id, new Variant(true, n.path("active").asBoolean(), n.path("sku").asString(), n.path("name_vi").asString(),
            n.path("name_en").asString(), n.path("size").asString(), n.path("color").asString(), price.longValue(),
            n.path("product_version").asLong(), n.path("image").path("asset_id").asString(null)));
      }
      if (!result.keySet().containsAll(variantIds)) throw new DependencyUnavailable("catalog");
      return result;
    } catch (DependencyUnavailable e) { throw e; }
    catch (RuntimeException e) { throw new DependencyUnavailable("catalog"); }
  }
}
