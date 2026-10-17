package vn.fashion.catalog.product;

import static org.assertj.core.api.Assertions.assertThat;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.fashion.catalog.admin.CatalogAdminTestSupport;

/** CAT-02b spec section 6: success max-age per route; every public error no-store. */
class PublicCacheHeadersIntegrationTest extends CatalogAdminTestSupport {
  private UUID category, product;

  @BeforeEach void seed() {
    var fixtures = new PublicCatalogFixtures(jdbc);
    category = fixtures.category(null, "shirts", "ACTIVE");
    product = fixtures.product("cached", "Áo", category, null, "ACTIVE", 100000, Instant.now(), 0);
    fixtures.variant(product, "CACHE-M", "M", "Blue", null, "ACTIVE");
    UUID collection = fixtures.collection("summer", "ACTIVE", Instant.now().minusSeconds(60), null);
    fixtures.item(collection, product, 0);
    fixtures.sizeGuide(category, "vi");
  }

  private HttpResponse<String> get(String path) { return send("GET", "/api/v1/catalog" + path, null, null, null); }
  private static String cache(HttpResponse<String> r) { return r.headers().firstValue("Cache-Control").orElse(null); }

  @Test void everyPublicRouteSendsItsCacheControl() {
    var expected = new LinkedHashMap<String, String>();
    expected.put("/products", "public, max-age=30");
    expected.put("/search?q=a", "public, max-age=30");
    expected.put("/products/" + product, "public, max-age=5");
    expected.put("/categories", "public, max-age=30");
    expected.put("/brands", "public, max-age=30");
    expected.put("/collections", "public, max-age=30");
    expected.put("/collections/summer", "public, max-age=60");
    expected.put("/size-guides/" + category, "public, max-age=60");
    for (Map.Entry<String, String> e : expected.entrySet()) {
      var response = get(e.getKey());
      assertThat(response.statusCode()).as(e.getKey()).isEqualTo(200);
      assertThat(cache(response)).as(e.getKey()).isEqualTo(e.getValue());
    }
  }

  @Test void errorsAreNoStore() {
    for (String path : new String[] {"/products?limit=0", "/products/" + UUID.randomUUID(), "/products/not-a-uuid",
        "/collections/missing", "/size-guides/" + category + "?locale=fr", "/search"}) {
      var response = get(path);
      assertThat(response.statusCode()).as(path).isBetween(400, 499);
      assertThat(cache(response)).as(path).isEqualTo("no-store");
    }
  }

  @Test void detailAndSizeGuideVaryOnLanguage() {
    assertThat(get("/products/" + product).headers().firstValue("Vary")).hasValueSatisfying(v -> assertThat(v).contains("Accept-Language"));
    assertThat(get("/size-guides/" + category).headers().firstValue("Vary")).hasValueSatisfying(v -> assertThat(v).contains("Accept-Language"));
  }
}
