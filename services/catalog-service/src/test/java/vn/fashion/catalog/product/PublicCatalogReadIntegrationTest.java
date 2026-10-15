package vn.fashion.catalog.product;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import vn.fashion.catalog.admin.CatalogAdminTestSupport;

import static org.assertj.core.api.Assertions.assertThat;

class PublicCatalogReadIntegrationTest extends CatalogAdminTestSupport {
  private static final String PUBLIC = "/api/v1/catalog";
  private PublicCatalogFixtures fixtures;
  private UUID category;

  @BeforeEach void setup() {
    fixtures = new PublicCatalogFixtures(jdbc);
    category = fixtures.category(null, "shirts", "ACTIVE");
  }

  @Test void detailShowsOnlyActiveVariantsAndAvailableImages() {
    UUID brand = fixtures.brand("Brand", "ACTIVE");
    UUID product = product("detail", category, brand, "ACTIVE");
    jdbc.update("update products set description_vi='<p>Mô tả</p>',description_en='<p>Description</p>',tags=array['cotton','summer'] where id=?", product);
    UUID regular = fixtures.variant(product, "REGULAR", "M", "Black", null, "ACTIVE");
    UUID cheap = fixtures.variant(product, "CHEAP", "L", "White", 150000L, "ACTIVE");
    fixtures.variant(product, "HIDDEN", "S", "Red", 1000L, "INACTIVE");
    fixtures.image(product, 0, "DELETING");
    fixtures.image(product, 0, "DELETED");
    UUID later = fixtures.image(product, 2, "AVAILABLE");
    UUID first = fixtures.image(product, 1, "AVAILABLE");
    jdbc.update("update product_images set variant_color='White' where asset_id=?", first);

    JsonNode detail = data(get("/products/" + product), 200);
    assertThat(detail.path("id").asText()).isEqualTo(product.toString());
    assertThat(detail.path("slug").asText()).isEqualTo("detail");
    assertThat(detail.path("name_vi").asText()).isEqualTo("Áo");
    assertThat(detail.path("name_en").asText()).isEqualTo("English shirt");
    assertThat(detail.path("description_vi").asText()).isEqualTo("<p>Mô tả</p>");
    assertThat(detail.path("description_en").asText()).isEqualTo("<p>Description</p>");
    assertThat(texts(detail.path("tags"))).containsExactly("cotton", "summer");
    assertThat(detail.path("category").path("id").asText()).isEqualTo(category.toString());
    assertThat(detail.path("brand").path("id").asText()).isEqualTo(brand.toString());
    assertThat(detail.path("brand").path("name").asText()).isEqualTo("Brand");
    assertThat(detail.path("min_price").asLong()).isEqualTo(150000);
    assertThat(detail.path("max_price").asLong()).isEqualTo(200000);
    assertThat(ids(detail.path("variants"))).containsExactlyInAnyOrder(regular.toString(), cheap.toString());
    for (JsonNode variant : detail.path("variants")) {
      boolean discounted = variant.path("id").asText().equals(cheap.toString());
      assertThat(variant.path("sku").asText()).isEqualTo(discounted ? "CHEAP" : "REGULAR");
      assertThat(variant.path("size").asText()).isEqualTo(discounted ? "L" : "M");
      assertThat(variant.path("color").asText()).isEqualTo(discounted ? "White" : "Black");
      assertThat(variant.path("effective_price").asLong()).isEqualTo(discounted ? 150000 : 200000);
    }
    assertThat(detail.path("images").size()).isEqualTo(2);
    JsonNode image = detail.path("images").get(0);
    assertThat(image.path("asset_id").asText()).isEqualTo(first.toString());
    assertThat(image.path("alt_vi").asText()).isEqualTo("Ảnh");
    assertThat(image.path("alt_en").asText()).isEqualTo("Image");
    assertThat(image.path("variant_color").asText()).isEqualTo("White");
    assertThat(image.path("sort_order").asInt()).isEqualTo(1);
    assertThat(detail.path("images").get(1).path("asset_id").asText()).isEqualTo(later.toString());
    for (String field : List.of("available", "observed_at", "size_guide")) {
      assertThat(detail.has(field)).as(field).isTrue();
      assertThat(detail.path(field).isNull()).as(field).isTrue();
    }
    assertThat(jdbc.queryForObject("select count(*) from audit_logs", Integer.class)).isZero();
  }

  @Test void detailUsesLocaleForCategoryNameAndSizeGuide() {
    UUID product = publicProduct("localized", category, null);
    fixtures.sizeGuide(category, "vi");
    fixtures.sizeGuide(category, "en");
    for (String language : List.of("", "vi", "fr", "en-US,en;q=0.9", "EN")) {
      boolean english = language.startsWith("en") || language.equals("EN");
      JsonNode detail = data(get("/products/" + product, language), 200);
      assertThat(detail.path("category").path("name").asText()).isEqualTo(english ? "Shirt" : "Áo");
      assertThat(detail.path("brand").isNull()).isTrue();
      assertGuide(detail.path("size_guide"), category, english ? "en" : "vi");
    }
  }

  @Test void nonPublicProductIs404() {
    UUID draft = product("draft", category, null, "DRAFT");
    fixtures.variant(draft, "DRAFT", "M", "Black", null, "ACTIVE");
    UUID inactive = product("inactive", category, null, "INACTIVE");
    fixtures.variant(inactive, "INACTIVE", "M", "Black", null, "ACTIVE");
    UUID empty = product("empty", category, null, "ACTIVE");
    UUID hiddenVariants = product("hidden-variants", category, null, "ACTIVE");
    fixtures.variant(hiddenVariants, "HIDDEN-VARIANT", "M", "Black", null, "INACTIVE");
    UUID hiddenCategory = publicProduct("hidden-category", fixtures.category(null, "hidden", "INACTIVE"), null);
    UUID brand = fixtures.brand("Disabled after publish", "ACTIVE");
    UUID hiddenBrand = publicProduct("hidden-brand", category, brand);
    jdbc.update("update brands set status='INACTIVE' where id=?", brand);
    for (String id : List.of(draft.toString(), inactive.toString(), empty.toString(), hiddenVariants.toString(),
        hiddenCategory.toString(), hiddenBrand.toString(), UUID.randomUUID().toString(), "abc", "0-0-4000-8000-1")) {
      notFound(get("/products/" + id), "PRODUCT_NOT_FOUND");
    }
  }

  @Test void categoriesTreeHidesInactiveBranches() {
    UUID child = fixtures.category(category, "child", "ACTIVE");
    UUID later = fixtures.category(category, "later-child", "ACTIVE");
    jdbc.update("update categories set sort_order=2 where id=?", later);
    fixtures.category(category, "inactive-child", "INACTIVE");
    UUID hiddenRoot = fixtures.category(null, "inactive-root", "INACTIVE");
    fixtures.category(hiddenRoot, "active-under-inactive", "ACTIVE");
    JsonNode tree = data(get("/categories"), 200);
    assertThat(ids(tree)).containsExactly(category.toString(), "00000000-0000-4000-8000-000000000001");
    JsonNode root = tree.get(0);
    assertThat(root.path("slug").asText()).isEqualTo("shirts");
    assertThat(root.path("name_vi").asText()).isEqualTo("Áo");
    assertThat(root.path("name_en").asText()).isEqualTo("Shirt");
    assertThat(ids(root.path("children"))).containsExactly(child.toString(), later.toString());
    assertThat(root.path("children").get(0).path("children").isArray()).isTrue();
    assertThat(root.path("children").get(0).path("children").isEmpty()).isTrue();
    assertThat(tree.get(1).path("children").isEmpty()).isTrue();
  }

  @Test void brandsListOnlyActive() {
    UUID z = fixtures.brand("Z Brand", "ACTIVE");
    UUID a = fixtures.brand("A Brand", "ACTIVE");
    fixtures.brand("Hidden", "INACTIVE");
    JsonNode brands = data(get("/brands"), 200);
    assertThat(ids(brands)).containsExactly(a.toString(), z.toString());
    assertThat(brands.get(0).path("name").asText()).isEqualTo("A Brand");
    assertThat(brands.get(1).path("name").asText()).isEqualTo("Z Brand");
  }

  @Test void sizeGuidePublicRequiresActiveCategoryAndValidLocale() {
    fixtures.sizeGuide(category, "vi");
    fixtures.sizeGuide(category, "en");
    assertGuide(data(get("/size-guides/" + category), 200), category, "vi");
    assertGuide(data(get("/size-guides/" + category, "en-GB"), 200), category, "en");
    assertGuide(data(get("/size-guides/" + category + "?locale=vi", "en"), 200), category, "vi");
    assertGuide(data(get("/size-guides/" + category + "?locale=en"), 200), category, "en");
    for (String locale : List.of("fr", "VI", "")) {
      var response = get("/size-guides/" + category + "?locale=" + locale);
      data(response, 400);
      assertThat(json(response).path("code").asText()).isEqualTo("VALIDATION_ERROR");
      assertThat(json(response).path("errors").get(0).path("field").asText()).isEqualTo("locale");
    }
    UUID missing = fixtures.category(null, "no-guide", "ACTIVE");
    notFound(get("/size-guides/" + missing), "SIZE_GUIDE_NOT_FOUND");
    notFound(get("/size-guides/" + UUID.randomUUID()), "SIZE_GUIDE_NOT_FOUND");
    notFound(get("/size-guides/abc"), "SIZE_GUIDE_NOT_FOUND");
    notFound(get("/size-guides/0-0-4000-8000-1"), "SIZE_GUIDE_NOT_FOUND");
    jdbc.update("update categories set status='INACTIVE' where id=?", category);
    notFound(get("/size-guides/" + category), "SIZE_GUIDE_NOT_FOUND");
  }

  @Test void missingLocalizedGuideIsNullWithoutFallback() {
    UUID product = publicProduct("no-english-guide", category, null);
    fixtures.sizeGuide(category, "vi");
    JsonNode detail = data(get("/products/" + product, "en"), 200);
    assertThat(detail.path("size_guide").isNull()).isTrue();
    assertThat(detail.path("images").isEmpty()).isTrue();
    notFound(get("/size-guides/" + category + "?locale=en"), "SIZE_GUIDE_NOT_FOUND");
  }

  @Test void shortenedUuidsDoNotResolveExistingPublicResources() {
    UUID canonical = UUID.fromString("00000000-0000-4000-8000-000000000002");
    UUID original = product("canonical", category, null, "ACTIVE");
    jdbc.update("update products set id=? where id=?", canonical, original);
    fixtures.variant(canonical, "CANONICAL", "M", "Black", null, "ACTIVE");
    UUID seedCategory = UUID.fromString("00000000-0000-4000-8000-000000000001");
    fixtures.sizeGuide(seedCategory, "vi");
    data(get("/products/" + canonical), 200);
    data(get("/size-guides/" + seedCategory), 200);
    notFound(get("/products/0-0-4000-8000-2"), "PRODUCT_NOT_FOUND");
    notFound(get("/size-guides/0-0-4000-8000-1"), "SIZE_GUIDE_NOT_FOUND");
  }

  private UUID product(String slug, UUID categoryId, UUID brand, String status) {
    return fixtures.product(slug, "Áo", categoryId, brand, status, 200000, Instant.parse("2026-01-01T00:00:00Z"), 0);
  }

  private UUID publicProduct(String slug, UUID categoryId, UUID brand) {
    UUID id = product(slug, categoryId, brand, "ACTIVE");
    fixtures.variant(id, slug.toUpperCase(Locale.ROOT), "M", "Black", null, "ACTIVE");
    return id;
  }

  private HttpResponse<String> get(String path) { return get(path, ""); }

  private HttpResponse<String> get(String path, String language) {
    try {
      var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + PUBLIC + path));
      if (!language.isEmpty()) request.header("Accept-Language", language);
      return HttpClient.newHttpClient().send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    } catch (Exception e) { throw new AssertionError(e); }
  }

  private void notFound(HttpResponse<String> response, String message) {
    data(response, 404);
    assertThat(json(response).path("code").asText()).isEqualTo("NOT_FOUND");
    assertThat(json(response).path("message").asText()).isEqualTo(message);
    assertThat(json(response).path("errors").isEmpty()).isTrue();
  }

  private static void assertGuide(JsonNode guide, UUID categoryId, String locale) {
    assertThat(guide.path("category_id").asText()).isEqualTo(categoryId.toString());
    assertThat(guide.path("locale").asText()).isEqualTo(locale);
    assertThat(guide.path("guideline_html").asText()).isEqualTo("<p>" + locale + " guide</p>");
    assertThat(guide.path("table").path("columns").get(0).asText()).isEqualTo("Size");
    assertThat(guide.path("table").path("rows").get(0).get(0).asText()).isEqualTo("M");
  }

  private static List<String> ids(JsonNode nodes) {
    return nodes.valueStream().map(node -> node.path("id").asText()).toList();
  }

  private static List<String> texts(JsonNode nodes) { return nodes.valueStream().map(JsonNode::asText).toList(); }
}
