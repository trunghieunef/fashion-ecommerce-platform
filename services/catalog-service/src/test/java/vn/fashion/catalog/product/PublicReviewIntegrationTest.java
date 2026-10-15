package vn.fashion.catalog.product;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import vn.fashion.catalog.admin.CatalogAdminTestSupport;

import static org.assertj.core.api.Assertions.assertThat;

class PublicReviewIntegrationTest extends CatalogAdminTestSupport {
  private static final String ROOT = "/api/v1/catalog";

  @Test void inactiveParentHidesChildProductsAcrossPublicReadsAndCollectionMembership() {
    var fixtures = new PublicCatalogFixtures(jdbc);
    UUID parent = fixtures.category(null, "parent", "ACTIVE");
    UUID child = fixtures.category(parent, "child", "ACTIVE");
    UUID standalone = fixtures.category(null, "standalone", "ACTIVE");
    UUID visible = product(fixtures, standalone, "visible");
    UUID hidden = product(fixtures, child, "hidden");
    UUID mixed = fixtures.collection("mixed", "ACTIVE", null, null);
    UUID sole = fixtures.collection("sole", "ACTIVE", null, null);
    fixtures.item(mixed, hidden, 0);
    fixtures.item(mixed, visible, 1);
    fixtures.item(sole, hidden, 0);
    assertThat(ids(page("/products").path("items"))).containsExactlyInAnyOrder(visible.toString(), hidden.toString());
    assertThat(page("/products/" + hidden).path("id").asText()).isEqualTo(hidden.toString());
    jdbc.update("update categories set status='INACTIVE' where id=?", parent);

    for (String path : List.of("/products", "/search?q=English")) {
      assertThat(ids(page(path).path("items"))).containsExactly(visible.toString());
    }
    assertThat(ids(page("/products?category_id=" + parent).path("items"))).isEmpty();
    assertThat(send("GET", ROOT + "/products/" + hidden, null, null, null).statusCode()).isEqualTo(404);
    assertThat(ids(page("/collections").path("items"))).containsExactly(mixed.toString());
    assertThat(ids(page("/collections/mixed").path("products"))).containsExactly(visible.toString());
    assertThat(send("GET", ROOT + "/collections/sole", null, null, null).statusCode()).isEqualTo(404);
  }

  @Test void invalidLimitsUseTheSharedFieldErrorEnvelopeWithoutEchoingInput() {
    for (String path : List.of("/products?", "/search?q=English&", "/collections?", "/collections/missing?")) {
      for (String limit : List.of("0", "abc")) {
        var response = send("GET", ROOT + path + "limit=" + limit, null, null, null);
        assertThat(response.statusCode()).as(path + limit).isEqualTo(400);
        JsonNode body = json(response);
        assertThat(body.path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(body.path("message").asText()).isEqualTo("INVALID_LIMIT");
        assertThat(body.path("errors")).hasSize(1);
        JsonNode error = body.path("errors").get(0);
        assertThat(error.size()).isEqualTo(2);
        assertThat(error.path("field").asText()).isEqualTo("limit");
        assertThat(error.path("message").asText()).isEqualTo("must be between 1 and 100");
        assertThat(error.has("rejected_value")).isFalse();
        assertThat(body.path("metadata").path("request_id").asText()).satisfies(UUID::fromString);
        assertThat(body.path("metadata").path("trace_id").asText()).matches("[0-9a-f]{32}");
        assertThat(response.headers().firstValue("X-Correlation-Id"))
            .contains(body.path("metadata").path("trace_id").asText());
      }
    }
  }

  @Test void repeatedAndUnknownQueryParametersAreToleratedUsingTheFirstValue() {
    var fixtures = new PublicCatalogFixtures(jdbc);
    UUID category = fixtures.category(null, "query", "ACTIVE");
    UUID product = product(fixtures, category, "query");
    UUID collection = fixtures.collection("query", "ACTIVE", null, null);
    fixtures.item(collection, product, 0);
    for (String path : List.of("/products?limit=20&limit=abc&extra=ignored",
        "/search?q=English&q=missing&limit=20&limit=abc&extra=ignored",
        "/collections?limit=20&limit=abc&extra=ignored", "/collections/query?limit=20&limit=abc&extra=ignored")) {
      JsonNode result = page(path);
      if (path.startsWith("/collections/query")) assertThat(ids(result.path("products"))).containsExactly(product.toString());
      else assertThat(ids(result.path("items"))).containsExactly(path.startsWith("/collections") ? collection.toString() : product.toString());
    }
  }

  @Test void unsupportedProductMethodRemains405() {
    assertThat(send("POST", ROOT + "/products", null, null, "{}").statusCode()).isEqualTo(405);
  }

  private UUID product(PublicCatalogFixtures fixtures, UUID category, String slug) {
    UUID product = fixtures.product(slug, "Shirt", category, null, "ACTIVE", 200000, Instant.now(), 0);
    fixtures.variant(product, slug.toUpperCase(java.util.Locale.ROOT), "M", "Black", null, "ACTIVE");
    return product;
  }

  private JsonNode page(String path) { return data(send("GET", ROOT + path, null, null, null), 200); }
  private List<String> ids(JsonNode items) {
    var result = new ArrayList<String>();
    items.forEach(item -> result.add(item.path("id").asText()));
    return result;
  }
}
