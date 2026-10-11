package vn.fashion.catalog.product;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import vn.fashion.catalog.admin.CatalogAdminTestSupport;

import static org.assertj.core.api.Assertions.assertThat;

class ProductBrowseIntegrationTest extends CatalogAdminTestSupport {
  private static final String PRODUCTS = "/api/v1/catalog/products";
  private static final Instant PUBLISHED = Instant.parse("2026-01-01T00:00:00Z");
  private PublicCatalogFixtures fixtures;
  private UUID category;

  @BeforeEach void setup() {
    fixtures = new PublicCatalogFixtures(jdbc);
    category = fixtures.category(null, "shirts", "ACTIVE");
  }

  @Test void hidesNonPublicProducts() {
    UUID valid = publicProduct("valid", category, null, 200000);
    UUID branded = publicProduct("branded", category, fixtures.brand("Active", "ACTIVE"), 200000);
    UUID draft = fixtures.product("draft", "Áo", category, null, "DRAFT", 200000, PUBLISHED, 0);
    fixtures.variant(draft, "DRAFT", "M", "Black", null, "ACTIVE");
    UUID hidden = fixtures.product("inactive", "Áo", category, null, "INACTIVE", 200000, PUBLISHED, 0);
    fixtures.variant(hidden, "INACTIVE-PRODUCT", "M", "Black", null, "ACTIVE");
    fixtures.product("no-variants", "Áo", category, null, "ACTIVE", 200000, PUBLISHED, 0);
    UUID inactive = fixtures.product("inactive-variants", "Áo", category, null, "ACTIVE", 200000, PUBLISHED, 0);
    fixtures.variant(inactive, "INACTIVE", "M", "Black", null, "INACTIVE");
    publicProduct("hidden-category", fixtures.category(null, "hidden", "INACTIVE"), null, 200000);
    publicProduct("hidden-brand", category, fixtures.brand("Hidden", "INACTIVE"), 200000);
    assertThat(ids(page(PRODUCTS))).containsExactlyInAnyOrder(valid.toString(), branded.toString());
  }

  @Test void cardCarriesMinMaxPriceAndFirstAvailableImage() {
    UUID product = publicProduct("card", category, null, 200000);
    fixtures.variant(product, "LOW", "L", "White", 150000L, "ACTIVE");
    fixtures.variant(product, "HIDDEN", "S", "Red", 1000L, "INACTIVE");
    fixtures.image(product, 0, "DELETING");
    fixtures.image(product, 0, "DELETED");
    UUID first = fixtures.image(product, 1, "AVAILABLE");
    UUID tied = fixtures.image(product, 1, "AVAILABLE");
    String expected = List.of(first.toString(), tied.toString()).stream().min(String::compareTo).orElseThrow();
    JsonNode card = page(PRODUCTS).path("items").get(0);
    assertThat(card.path("min_price").asLong()).isEqualTo(150000);
    assertThat(card.path("max_price").asLong()).isEqualTo(200000);
    assertThat(card.path("name_vi").asText()).isEqualTo("Áo");
    assertThat(card.path("name_en").asText()).isEqualTo("English shirt");
    assertThat(card.path("image").path("asset_id").asText()).isEqualTo(expected);
    assertThat(card.path("image").path("alt_vi").asText()).isEqualTo("Ảnh");
    assertThat(card.path("image").path("alt_en").asText()).isEqualTo("Image");
  }

  @Test void sizeAndColorMustMatchTheSameVariant() {
    UUID product = publicProduct("variants", category, null, 200000);
    fixtures.variant(product, "WHITE", "L", "White", null, "ACTIVE");
    fixtures.variant(product, "INACTIVE-M-WHITE", "M", "White", null, "INACTIVE");
    assertThat(ids(page(PRODUCTS + "?size=M&color=White"))).isEmpty();
    assertThat(ids(page(PRODUCTS + "?size=m&color=black"))).containsExactly(product.toString());
    assertThat(ids(page(PRODUCTS + "?size=l"))).containsExactly(product.toString());
    assertThat(ids(page(PRODUCTS + "?color=white"))).containsExactly(product.toString());
  }

  @Test void categoryParentIncludesChildren() {
    UUID own = publicProduct("own", category, null, 200000);
    UUID child = publicProduct("child", fixtures.category(category, "child", "ACTIVE"), null, 200000);
    publicProduct("other", fixtures.category(null, "other", "ACTIVE"), null, 200000);
    assertThat(ids(page(PRODUCTS + "?category_id=" + category))).containsExactlyInAnyOrder(own.toString(), child.toString());
  }

  @Test void brandTagAndPriceRangeFilterOnMinPrice() {
    UUID brand = fixtures.brand("Brand", "ACTIVE");
    UUID product = fixtures.product("filtered", "Áo", category, brand, "ACTIVE", 200000, PUBLISHED, 0, "cotton");
    fixtures.variant(product, "FILTERED", "M", "Black", 150000L, "ACTIVE");
    publicProduct("other", category, null, 150000);
    String query = PRODUCTS + "?brand_id=" + brand + "&tag=cotton&min_price=150000&max_price=150000";
    assertThat(ids(page(query))).containsExactly(product.toString());
    assertThat(ids(page(PRODUCTS + "?max_price=149999"))).isEmpty();
    assertThat(ids(page(PRODUCTS + "?min_price=150001"))).isEmpty();
    assertThat(ids(page(PRODUCTS + "?tag=linen"))).isEmpty();
  }

  @Test void searchIgnoresAccentsAndEscapesWildcards() {
    UUID product = fixtures.product("ao-so-mi", "Áo sơ mi", category, null, "ACTIVE", 200000, PUBLISHED, 0);
    fixtures.variant(product, "SEARCH", "M", "Black", null, "ACTIVE");
    assertThat(ids(page("/api/v1/catalog/search?q=ao+so+mi"))).containsExactly(product.toString());
    assertThat(ids(page(PRODUCTS + "?q=ENGLISH"))).containsExactly(product.toString());
    assertThat(ids(page(PRODUCTS + "?q=ao-so"))).containsExactly(product.toString());
    for (String literal : List.of("%", "_", "\\", "' OR 1=1 --")) {
      assertThat(ids(page(PRODUCTS + "?q=" + encode(literal)))).isEmpty();
    }
    fields("/api/v1/catalog/search", "q");
  }

  @Test void everySortPagesWithoutDuplicatesOrGaps() {
    var tied = new ArrayList<String>();
    for (int i = 0; i < 7; i++) tied.add(publicProduct("tied-" + i, category, null, 150000).toString());
    UUID low = fixtures.product("low", "Áo", category, null, "ACTIVE", 100000, PUBLISHED.minusSeconds(10), 1);
    fixtures.variant(low, "LOW-SORT", "M", "Black", null, "ACTIVE");
    UUID high = fixtures.product("high", "Áo", category, null, "ACTIVE", 250000, PUBLISHED.plusSeconds(10), 10);
    fixtures.variant(high, "HIGH-SORT", "M", "Black", null, "ACTIVE");
    for (String sort : List.of("NEWEST", "BEST_SELLING", "PRICE_ASC", "PRICE_DESC")) {
      var expected = new ArrayList<String>(tied);
      expected.sort(sort.equals("PRICE_ASC") ? Comparator.naturalOrder() : Comparator.reverseOrder());
      if (sort.equals("BEST_SELLING")) { expected.add(0, low.toString()); expected.add(0, high.toString()); }
      else if (sort.equals("PRICE_ASC")) { expected.add(0, low.toString()); expected.add(high.toString()); }
      else { expected.add(0, high.toString()); expected.add(low.toString()); }
      var actual = new ArrayList<String>();
      String cursor = null;
      int pages = 0;
      do {
        JsonNode result = page(PRODUCTS + "?limit=2&sort=" + sort + (cursor == null ? "" : "&cursor=" + cursor));
        actual.addAll(ids(result));
        cursor = result.path("next_cursor").isNull() ? null : result.path("next_cursor").asText();
        assertThat(++pages).isLessThanOrEqualTo(5);
      } while (cursor != null);
      assertThat(actual).containsExactlyElementsOf(expected).doesNotHaveDuplicates();
    }
  }

  @Test void cursorFromOtherFiltersOrSortIsRejected() {
    publicProduct("one", category, null, 150000);
    publicProduct("two", category, null, 150000);
    String cursor = page(PRODUCTS + "?limit=1").path("next_cursor").asText();
    fields(PRODUCTS + "?cursor=" + cursor + "&size=M", "cursor");
    fields(PRODUCTS + "?cursor=" + cursor + "&sort=PRICE_ASC", "cursor");
    fields(PRODUCTS + "?cursor=broken", "cursor");
  }

  @Test void invalidParametersReturnFieldErrors() {
    fields(PRODUCTS + "?category_id=x&sort=CHEAP", "category_id", "sort");
    fields(PRODUCTS + "?min_price=2&max_price=1", "max_price");
    fields(PRODUCTS + "?brand_id=x&min_price=-1&size=", "brand_id", "min_price", "size");
    var response = send("GET", PRODUCTS + "?limit=101", null, null, null);
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(json(response).path("message").asText()).isEqualTo("INVALID_LIMIT");
    assertThat(json(response).path("errors").get(0).path("field").asText()).isEqualTo("limit");
  }

  @Test void legacyActiveWithoutPublishedAtSortsByCreatedAt() {
    UUID old = publicProduct("published", category, null, 200000);
    UUID legacy = fixtures.product("legacy", "Áo", category, null, "ACTIVE", 200000, null, 0);
    fixtures.variant(legacy, "LEGACY", "M", "Black", null, "ACTIVE");
    jdbc.update("update products set created_at='2026-02-01T00:00:00Z' where id=?", legacy);
    JsonNode first = page(PRODUCTS + "?limit=1");
    assertThat(ids(first)).containsExactly(legacy.toString());
    assertThat(ids(page(PRODUCTS + "?limit=1&cursor=" + first.path("next_cursor").asText()))).containsExactly(old.toString());
    assertThat(first.path("items").get(0).path("image").isNull()).isTrue();
  }

  @Test void newestCursorRejectsOutOfRangeInstantsBeforeSqlAndAcceptsBoundaries() {
    var filters = BrowseFilters.parse(java.util.Map.of(), false);
    UUID product = publicProduct("cursor-domain", category, null, 200000);
    for (String value : List.of("+300000-01-01T00:00:00Z", "+1000000000-01-01T00:00:00Z",
        "0000-12-31T23:59:59Z", "+10000-01-01T00:00:00Z")) {
      String cursor = new ProductCursor("NEWEST", value, product, filters.hash()).encode();
      assertThat(fields(PRODUCTS + "?cursor=" + cursor, "cursor").path("message").asText()).isEqualTo("INVALID_CURSOR");
    }
    assertThat(ids(page(PRODUCTS + "?cursor=" + new ProductCursor("NEWEST", "0001-01-01T00:00:00Z",
        product, filters.hash()).encode()))).isEmpty();
    assertThat(ids(page(PRODUCTS + "?cursor=" + new ProductCursor("NEWEST", "9999-12-31T23:59:59.999999Z",
        product, filters.hash()).encode()))).containsExactly(product.toString());
  }

  private UUID publicProduct(String slug, UUID category, UUID brand, long price) {
    UUID product = fixtures.product(slug, "Áo", category, brand, "ACTIVE", price, PUBLISHED, 0);
    fixtures.variant(product, slug.toUpperCase(java.util.Locale.ROOT), "M", "Black", null, "ACTIVE");
    return product;
  }

  private JsonNode page(String path) { return data(send("GET", path, null, null, null), 200); }
  private List<String> ids(JsonNode page) {
    var ids = new ArrayList<String>();
    page.path("items").forEach(item -> ids.add(item.path("id").asText()));
    return ids;
  }
  private JsonNode fields(String path, String... expected) {
    var response = send("GET", path, null, null, null);
    assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(400);
    JsonNode body = json(response);
    assertThat(body.path("code").asText()).isEqualTo("VALIDATION_ERROR");
    var actual = new ArrayList<String>();
    body.path("errors").forEach(error -> actual.add(error.path("field").asText()));
    assertThat(actual).containsExactlyInAnyOrder(expected);
    assertThat(response.headers().firstValue("X-Correlation-Id")).contains(body.path("metadata").path("trace_id").asText());
    return body;
  }
  private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
