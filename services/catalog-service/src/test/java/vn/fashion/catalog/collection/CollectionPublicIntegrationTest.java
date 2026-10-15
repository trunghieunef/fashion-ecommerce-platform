package vn.fashion.catalog.collection;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import vn.fashion.catalog.admin.CatalogAdminTestSupport;
import vn.fashion.catalog.product.ProductCursor;
import vn.fashion.catalog.product.PublicCatalogFixtures;

import static org.assertj.core.api.Assertions.assertThat;

class CollectionPublicIntegrationTest extends CatalogAdminTestSupport {
  private static final String COLLECTIONS = "/api/v1/catalog/collections";
  private PublicCatalogFixtures fixtures;
  private UUID category;
  @Autowired private PublicCollectionRepository collections;
  @Autowired private PlatformTransactionManager transactions;

  @BeforeEach void setup() {
    fixtures = new PublicCatalogFixtures(jdbc);
    category = fixtures.category(null, "shirts", "ACTIVE");
  }

  @Test void emptyCollectionIsHidden() {
    fixtures.collection("empty", "ACTIVE", null, null);
    hidden("empty");
    notFound("missing");
  }

  @Test void collectionWithOnlyUnpublishedProductsIsHidden() {
    UUID collection = fixtures.collection("unpublished", "ACTIVE", null, null);
    for (String status : List.of("DRAFT", "INACTIVE")) {
      UUID product = fixtures.product(status.toLowerCase(Locale.ROOT), "Áo", category, null, status, 200000, null, 0);
      fixtures.variant(product, status, "M", "Black", null, "ACTIVE");
      fixtures.item(collection, product, 0);
    }
    hidden("unpublished");
  }

  @Test void futureStartIsHidden() {
    UUID collection = collection("future");
    jdbc.update("update collections set start_at=now()+interval '1 hour' where id=?", collection);
    hidden("future");
  }

  @Test void inactiveAndDraftCollectionsAreHidden() {
    for (String status : List.of("DRAFT", "INACTIVE")) {
      UUID collection = collection(status.toLowerCase(Locale.ROOT));
      jdbc.update("update collections set status=? where id=?", status, collection);
      notFound(status.toLowerCase(Locale.ROOT));
    }
    assertThat(ids(page(COLLECTIONS).path("items"))).isEmpty();
  }

  @Test void startBoundaryShowsAndEndBoundaryHides() {
    Instant dbNow = jdbc.queryForObject("select now()", Timestamp.class).toInstant();
    UUID collection = collection("boundary");
    jdbc.update("update collections set start_at=? where id=?", Timestamp.from(dbNow.minusSeconds(1)), collection);
    assertThat(ids(page(COLLECTIONS).path("items"))).containsExactly(collection.toString());
    assertThat(page(COLLECTIONS + "/boundary").path("collection").path("id").asText()).isEqualTo(collection.toString());
    jdbc.update("update collections set end_at=now() where id=?", collection);
    hidden("boundary");
  }

  @Test void exactDatabaseTransactionStartIncludesAndEndExcludesEquality() {
    UUID collection = collection("exact-boundary");
    new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
      jdbc.update("update collections set start_at=now() where id=?", collection);
      assertThat(collections.list(null, 20).items()).extracting(PublicCollectionRepository.CollectionSummary::id)
          .containsExactly(collection);
      assertThat(collections.detail("exact-boundary", null, 20)).isPresent();
      jdbc.update("update collections set start_at=now()-interval '1 second',end_at=now() where id=?", collection);
      assertThat(collections.list(null, 20).items()).isEmpty();
      assertThat(collections.detail("exact-boundary", null, 20)).isEmpty();
    });
  }

  @Test void detailListsOnlyPublicProductsInSortOrder() {
    UUID collection = fixtures.collection("ordered", "ACTIVE", null, null);
    UUID first = product("first", category, null);
    UUID last = product("last", category, null);
    fixtures.item(collection, last, 10);
    fixtures.item(collection, first, 1);
    UUID inactive = product("inactive", category, null);
    jdbc.update("update products set status='INACTIVE' where id=?", inactive);
    UUID noVariant = product("no-active-variant", category, null);
    jdbc.update("update product_variants set status='INACTIVE' where product_id=?", noVariant);
    UUID hiddenCategory = product("hidden-category", fixtures.category(null, "hidden", "INACTIVE"), null);
    UUID brand = fixtures.brand("Hidden", "ACTIVE");
    UUID hiddenBrand = product("hidden-brand", category, brand);
    jdbc.update("update brands set status='INACTIVE' where id=?", brand);
    for (UUID id : List.of(inactive, noVariant, hiddenCategory, hiddenBrand)) fixtures.item(collection, id, 0);
    fixtures.variant(first, "LOW", "L", "White", 150000L, "ACTIVE");
    fixtures.variant(first, "HIDDEN-LOW", "S", "Red", 1000L, "INACTIVE");
    fixtures.image(first, 0, "DELETING");
    UUID image = fixtures.image(first, 1, "AVAILABLE");
    JsonNode result = page(COLLECTIONS + "/ordered");
    assertThat(ids(result.path("products"))).containsExactly(first.toString(), last.toString());
    JsonNode card = result.path("products").get(0);
    assertThat(card.path("min_price").asLong()).isEqualTo(150000);
    assertThat(card.path("max_price").asLong()).isEqualTo(200000);
    assertThat(card.path("image").path("asset_id").asText()).isEqualTo(image.toString());
    assertThat(result.path("next_cursor").isNull()).isTrue();
  }

  @Test void collectionsNeedAProductMeetingEveryPublicCondition() {
    UUID brand = fixtures.brand("Brand", "INACTIVE");
    UUID hiddenCategory = fixtures.category(null, "hidden", "INACTIVE");
    for (String slug : List.of("brand", "category", "variant")) {
      UUID collection = fixtures.collection(slug, "ACTIVE", null, null);
      UUID product = product(slug, slug.equals("category") ? hiddenCategory : category, slug.equals("brand") ? brand : null);
      if (slug.equals("variant")) jdbc.update("update product_variants set status='INACTIVE' where product_id=?", product);
      fixtures.item(collection, product, 0);
      notFound(slug);
    }
    assertThat(ids(page(COLLECTIONS).path("items"))).isEmpty();
  }

  @Test void unpublishingLastActiveProductHidesCollection() {
    UUID collection = collection("unpublish");
    assertThat(ids(page(COLLECTIONS).path("items"))).containsExactly(collection.toString());
    assertThat(page(COLLECTIONS + "/unpublish").path("products").size()).isEqualTo(1);
    jdbc.update("update products set status='INACTIVE' where id in (select product_id from collection_items where collection_id=?)", collection);
    hidden("unpublish");
  }

  @Test void lookbookShowsOnlyAvailableAssetsAndPaginatesProducts() {
    UUID collection = fixtures.collection("lookbook", "ACTIVE", null, null);
    var products = new ArrayList<UUID>();
    for (int i = 0; i < 3; i++) {
      UUID product = product("item-" + i, category, null);
      fixtures.item(collection, product, 5);
      products.add(product);
    }
    products.sort(Comparator.comparing(UUID::toString));
    UUID last = fixtures.lookbook(collection, 10);
    UUID first = fixtures.lookbook(collection, 1);
    for (String state : List.of("DELETING", "DELETED")) {
      UUID unavailable = fixtures.lookbook(collection, 0);
      jdbc.update("update media_assets set availability=? where id=?", state, unavailable);
    }
    jdbc.update("update collections set cover_asset_id=? where id=?", first, collection);
    JsonNode detail = page(COLLECTIONS + "/lookbook?limit=2");
    assertThat(detail.path("collection").path("cover_asset_id").asText()).isEqualTo(first.toString());
    assertThat(detail.path("collection").path("name_vi").asText()).isEqualTo("Bộ sưu tập");
    assertThat(detail.path("collection").path("name_en").asText()).isEqualTo("Collection");
    assertThat(detail.path("collection").path("start_at").isNull()).isTrue();
    assertThat(detail.path("collection").path("end_at").isNull()).isTrue();
    JsonNode lookbook = detail.path("lookbook");
    assertThat(lookbook.size()).isEqualTo(2);
    assertThat(lookbook.get(0).path("asset_id").asText()).isEqualTo(first.toString());
    assertThat(lookbook.get(1).path("asset_id").asText()).isEqualTo(last.toString());
    assertThat(lookbook.get(0).path("caption_vi").asText()).isEqualTo("Chú thích");
    assertThat(lookbook.get(0).path("caption_en").asText()).isEqualTo("Caption");
    assertThat(lookbook.get(0).path("sort_order").asInt()).isEqualTo(1);
    assertThat(lookbook.get(0).properties().stream().map(java.util.Map.Entry::getKey)).containsExactlyInAnyOrder("asset_id", "caption_vi", "caption_en", "sort_order");
    assertThat(ids(detail.path("products"))).containsExactly(products.get(0).toString(), products.get(1).toString());
    String cursor = detail.path("next_cursor").asText();
    assertThat(cursor).isNotBlank();
    JsonNode second = page(COLLECTIONS + "/lookbook?limit=2&cursor=" + cursor);
    assertThat(ids(second.path("products"))).containsExactly(products.get(2).toString());
    assertThat(second.path("next_cursor").isNull()).isTrue();
    collection("other");
    invalid(COLLECTIONS + "/other?cursor=" + cursor, "cursor");
    invalid(COLLECTIONS + "?cursor=" + cursor, "cursor");
  }

  @Test void unavailableCoverIsOmittedFromPublicListAndDetail() {
    UUID collection = collection("cover");
    UUID cover = fixtures.lookbook(collection, 0);
    jdbc.update("update collections set cover_asset_id=? where id=?", cover, collection);
    assertThat(page(COLLECTIONS).path("items").get(0).path("cover_asset_id").asText()).isEqualTo(cover.toString());
    for (String state : List.of("DELETING", "DELETED")) {
      jdbc.update("update media_assets set availability=? where id=?", state, cover);
      assertThat(page(COLLECTIONS).path("items").get(0).path("cover_asset_id").isNull()).isTrue();
      JsonNode detail = page(COLLECTIONS + "/cover");
      assertThat(detail.path("collection").path("cover_asset_id").isNull()).isTrue();
      assertThat(detail.path("lookbook").size()).isZero();
    }
  }

  @Test void listPaginatesAndRejectsForeignCursor() {
    var collections = new ArrayList<UUID>();
    for (int i = 0; i < 3; i++) collections.add(collection("collection-" + i));
    jdbc.update("update collections set created_at='2026-01-01T00:00:00Z'");
    collections.sort(Comparator.comparing(UUID::toString).reversed());
    JsonNode first = page(COLLECTIONS + "?limit=2");
    assertThat(ids(first.path("items"))).containsExactly(collections.get(0).toString(), collections.get(1).toString());
    String cursor = first.path("next_cursor").asText();
    assertThat(cursor).isNotBlank();
    JsonNode second = page(COLLECTIONS + "?limit=2&cursor=" + cursor);
    assertThat(ids(second.path("items"))).containsExactly(collections.get(2).toString());
    assertThat(second.path("next_cursor").isNull()).isTrue();
    String foreign = page("/api/v1/catalog/products?limit=1").path("next_cursor").asText();
    assertThat(foreign).isNotBlank();
    invalid(COLLECTIONS + "?cursor=" + foreign, "cursor");
    invalid(COLLECTIONS + "/collection-0?cursor=" + cursor, "cursor");
  }

  @Test void cursorTimestampDomainIsValidatedAtCollectionsHttpBoundary() {
    UUID collection = collection("cursor-domain");
    for (String value : List.of("0000-12-31T23:59:59Z", "+10000-01-01T00:00:00Z", "-999999999-01-01T00:00:00Z", "999999999999999999")) {
      invalid(COLLECTIONS + "?cursor=" + new ProductCursor("COLLECTIONS", value, collection, "").encode(), "cursor");
    }
    String lower = new ProductCursor("COLLECTIONS", "0001-01-01T00:00:00Z", collection, "").encode();
    String upper = new ProductCursor("COLLECTIONS", "9999-12-31T23:59:59.999999999Z", collection, "").encode();
    assertThat(ids(page(COLLECTIONS + "?cursor=" + lower).path("items"))).isEmpty();
    assertThat(ids(page(COLLECTIONS + "?cursor=" + upper).path("items"))).containsExactly(collection.toString());
  }

  @Test void invalidLimitAndProductCursorAre400() {
    UUID collection = collection("validation");
    for (String value : List.of("0", "101", "-1", "x", "", "2147483648")) {
      invalid(COLLECTIONS + "?limit=" + value, "limit");
      invalid(COLLECTIONS + "/validation?limit=" + value, "limit");
    }
    for (String value : List.of("-1", "2147483648", "9223372036854775808", "1.5")) {
      invalid(COLLECTIONS + "/validation?cursor=" + new ProductCursor("COLLECTION:validation", value, collection, "").encode(), "cursor");
    }
    for (String value : List.of("0", "2147483647")) {
      assertThat(page(COLLECTIONS + "/validation?cursor=" + new ProductCursor("COLLECTION:validation", value, new UUID(0, 0), "").encode()).path("products").size()).isEqualTo(value.equals("0") ? 1 : 0);
    }
    invalid(COLLECTIONS + "?cursor=", "cursor");
    invalid(COLLECTIONS + "/validation?cursor=broken", "cursor");
  }

  private UUID collection(String slug) {
    UUID collection = fixtures.collection(slug, "ACTIVE", null, null);
    fixtures.item(collection, product(slug, category, null), 0);
    return collection;
  }

  private UUID product(String slug, UUID category, UUID brand) {
    UUID product = fixtures.product(slug, "Áo", category, brand, "ACTIVE", 200000, null, 0);
    fixtures.variant(product, slug.toUpperCase(Locale.ROOT), "M", "Black", null, "ACTIVE");
    return product;
  }

  private JsonNode page(String path) { return data(send("GET", path, null, null, null), 200); }

  private List<String> ids(JsonNode items) {
    var ids = new ArrayList<String>();
    for (JsonNode item : items) ids.add(item.path("id").asText());
    return ids;
  }

  private void hidden(String slug) {
    assertThat(ids(page(COLLECTIONS).path("items"))).isEmpty();
    notFound(slug);
  }

  private void notFound(String slug) {
    var response = send("GET", COLLECTIONS + "/" + slug, null, null, null);
    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(json(response).path("code").asText()).isEqualTo("NOT_FOUND");
    assertThat(json(response).path("message").asText()).isEqualTo("COLLECTION_NOT_FOUND");
  }

  private void invalid(String path, String field) {
    var response = send("GET", path, null, null, null);
    assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(400);
    assertThat(json(response).path("code").asText()).isEqualTo("VALIDATION_ERROR");
    assertThat(json(response).path("errors").get(0).path("field").asText()).isEqualTo(field);
  }
}
