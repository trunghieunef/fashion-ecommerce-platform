package vn.fashion.catalog.product;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;
import vn.fashion.catalog.web.Api;
import vn.fashion.catalog.product.BrowseFilters.Sort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BrowseFiltersTest {
  private static final UUID ID = UUID.fromString("12345678-1234-1234-1234-123456789abc");

  @Test void defaultsAndTrim() {
    var f = BrowseFilters.parse(Map.of("q", "  Áo  "), false);
    assertThat(f.q()).isEqualTo("Áo");
    assertThat(f.sort()).isEqualTo(Sort.NEWEST);
    assertThat(BrowseFilters.parse(Map.of(), false).q()).isNull();
  }

  @Test void reportsEveryInvalidField() {
    var e = filterError(Map.of("category_id", "x", "brand_id", "y", "min_price", "-1",
        "max_price", "abc", "sort", "CHEAP", "size", "x".repeat(65), "color", "", "tag", " "), false);
    assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(e.code()).isEqualTo("VALIDATION_ERROR");
    assertThat(e.getMessage()).isEqualTo("INVALID_FILTER");
    assertThat(e.errors()).extracting(Api.FieldError::field).containsExactlyInAnyOrder(
        "category_id", "brand_id", "min_price", "max_price", "sort", "size", "color", "tag");
  }

  @Test void minAboveMaxAndBlankQ() {
    assertThat(fields(Map.of("min_price", "10", "max_price", "5"), false)).containsExactly("max_price");
    assertThat(fields(Map.of("q", "   "), true)).containsExactly("q");
    assertThat(fields(Map.of("q", "   "), false)).containsExactly("q");
    assertThat(fields(Map.of(), true)).containsExactly("q");
    assertThat(fields(Map.of("q", "a".repeat(101)), false)).containsExactly("q");
  }

  @Test void acceptsFilterBoundariesAndTypedValues() {
    var f = BrowseFilters.parse(Map.of("q", "a".repeat(100), "category_id", ID.toString(),
        "brand_id", ID.toString(), "size", "M".repeat(64), "color", "b".repeat(64), "tag", "t".repeat(64),
        "min_price", "0", "max_price", "9223372036854775807", "sort", "PRICE_DESC"), false);
    assertThat(f.categoryId()).isEqualTo(ID);
    assertThat(f.brandId()).isEqualTo(ID);
    assertThat(f.minPrice()).isZero();
    assertThat(f.maxPrice()).isEqualTo(Long.MAX_VALUE);
    assertThat(f.sort()).isEqualTo(Sort.PRICE_DESC);
    assertThat(f.size()).hasSize(64);
    assertThat(f.color()).hasSize(64);
    assertThat(f.tag()).hasSize(64);
  }

  @Test void rejectsOverflowAndShortUuid() {
    assertThat(fields(Map.of("min_price", "9223372036854775808", "max_price", "-1",
        "category_id", "1-1-1-1-1", "brand_id", ""), false))
        .containsExactlyInAnyOrder("min_price", "max_price", "category_id", "brand_id");
  }

  @Test void rawQueryIsPreservedForDatabaseNormalization() {
    assertThat(BrowseFilters.parse(Map.of("q", "50%_off\\"), false).q()).isEqualTo("50%_off\\");
    assertThat(BrowseFilters.parse(Map.of("q", " ÁO ĐỎ "), false).q()).isEqualTo("ÁO ĐỎ");
  }

  @Test void cursorRoundTripsAndIsBoundToFiltersAndSort() {
    var f = BrowseFilters.parse(Map.of("size", "M"), false);
    var c = new ProductCursor("NEWEST", "2026-10-11T00:00:00Z", ID, f.hash());
    assertThat(ProductCursor.decode(c.encode(), f)).isEqualTo(c);
    assertThat(c.encode()).matches("[A-Za-z0-9_-]+");
    assertCursorError(c.encode(), BrowseFilters.parse(Map.of("size", "L"), false));
    assertCursorError(c.encode(), BrowseFilters.parse(Map.of("size", "M", "sort", "PRICE_ASC"), false));
    assertCursorError("%%%", f);
    assertCursorError(raw("{\"s\":\"NEWEST\"}"), f);
  }

  @Test void cursorEncodingUsesOnlyWireFieldNames() {
    var json = new ObjectMapper().readTree(Base64.getUrlDecoder().decode(
        new ProductCursor("NEWEST", "2026-10-11T00:00:00Z", ID, "0123456789abcdef").encode()));
    assertThat(json.propertyNames()).containsExactlyInAnyOrder("s", "v", "id", "f");
    assertThat(json.get("v").asText()).isEqualTo("2026-10-11T00:00:00Z");
    assertThat(json.get("id").asText()).isEqualTo(ID.toString());
  }

  @Test void hashIgnoresCaseOfTextFiltersButPreservesTagCase() {
    assertThat(BrowseFilters.parse(Map.of("q", "ÁO", "size", "M", "color", "Black"), false).hash())
        .isEqualTo(BrowseFilters.parse(Map.of("q", "áo", "size", "m", "color", "black"), false).hash())
        .matches("[0-9a-f]{16}");
    var f = BrowseFilters.parse(Map.of("tag", "New"), false);
    assertCursorError(new ProductCursor("NEWEST", "2026-10-11T00:00:00Z", ID, f.hash()).encode(),
        BrowseFilters.parse(Map.of("tag", "new"), false));
  }

  @Test void hashUsesFixedFieldOrderAndSha256Prefix() {
    var f = BrowseFilters.parse(Map.of("q", " ÁO ", "category_id", ID.toString(),
        "brand_id", "12345678-1234-1234-1234-123456789def", "size", "M", "color", "Black",
        "tag", "New", "min_price", "0", "max_price", "10", "sort", "PRICE_ASC"), false);
    assertThat(f.hash()).isEqualTo("52c78184515ceedb");
  }

  @Test void normalizationDoesNotDependOnDefaultLocale() {
    var previous = Locale.getDefault();
    try {
      Locale.setDefault(Locale.forLanguageTag("tr"));
      assertThat(BrowseFilters.parse(Map.of("q", "I"), false).hash())
          .isEqualTo(BrowseFilters.parse(Map.of("q", "i"), false).hash());
    } finally {
      Locale.setDefault(previous);
    }
  }

  @Test void cursorCannotCrossEveryChangedFilter() {
    var f = BrowseFilters.parse(Map.of(), false);
    var cursor = new ProductCursor("NEWEST", "2026-10-11T00:00:00Z", ID, f.hash()).encode();
    for (var change : List.of(Map.of("q", "ao"), Map.of("category_id", ID.toString()),
        Map.of("brand_id", ID.toString()), Map.of("size", "M"), Map.of("color", "Black"),
        Map.of("tag", "new"), Map.of("min_price", "0"), Map.of("max_price", "10"))) {
      assertCursorError(cursor, BrowseFilters.parse(change, false));
    }
  }

  @Test void cursorCannotCrossSeparatorCollidingFilters() {
    var f = BrowseFilters.parse(Map.of("size", "M|Black", "color", "White"), false);
    var cursor = new ProductCursor("NEWEST", "2026-10-11T00:00:00Z", ID, f.hash()).encode();
    assertCursorError(cursor, BrowseFilters.parse(Map.of("size", "M", "color", "Black|White"), false));
    var withBackslash = BrowseFilters.parse(Map.of("size", "M|Black\\", "color", "White"), false);
    assertCursorError(new ProductCursor("NEWEST", "2026-10-11T00:00:00Z", ID, withBackslash.hash()).encode(),
        BrowseFilters.parse(Map.of("size", "M\\", "color", "Black|White"), false));
  }

  @Test void rejectsMalformedJsonMissingNullAndNonStringCursorFields() {
    var f = BrowseFilters.parse(Map.of(), false);
    assertCursorError(raw("{"), f);
    assertCursorError(raw("[]"), f);
    for (var field : List.of("s", "v", "id", "f")) {
      var json = new ObjectMapper().createObjectNode().put("s", "NEWEST")
          .put("v", "2026-10-11T00:00:00Z").put("id", ID.toString()).put("f", f.hash());
      json.remove(field);
      assertCursorError(raw(json.toString()), f);
      json.putNull(field);
      assertCursorError(raw(json.toString()), f);
      json.put(field, 1);
      assertCursorError(raw(json.toString()), f);
    }
  }

  @Test void rejectsUnparseableCursorValuesBeforeSql() {
    for (var sort : Sort.values()) {
      var f = BrowseFilters.parse(Map.of("sort", sort.name()), false);
      var invalid = sort == Sort.NEWEST ? List.of("", "123", "2026-99-11T00:00:00Z")
          : List.of("", "abc", "1.5", "9223372036854775808", "-1");
      for (var value : invalid) {
        assertCursorError(new ProductCursor(sort.name(), value, ID, f.hash()).encode(), f);
      }
      assertCursorError(raw("{\"s\":\"" + sort.name() + "\",\"v\":\"0\",\"id\":\"invalid\",\"f\":\"" + f.hash() + "\"}"), f);
    }
  }

  @Test void numericSortCursorsRoundTripAtBoundaries() {
    for (var sort : List.of(Sort.BEST_SELLING, Sort.PRICE_ASC, Sort.PRICE_DESC)) {
      var f = BrowseFilters.parse(Map.of("sort", sort.name()), false);
      for (var value : List.of("0", "9223372036854775807")) {
        var c = new ProductCursor(sort.name(), value, ID, f.hash());
        assertThat(ProductCursor.decode(c.encode(), f)).isEqualTo(c);
      }
    }
  }

  @Test void collectionContextsValidateValuesAndBinding() {
    var list = new ProductCursor("COLLECTIONS", "2026-10-11T00:00:00Z", ID, "");
    assertThat(ProductCursor.decode(list.encode(), "COLLECTIONS", "")).isEqualTo(list);
    for (var value : List.of("0", "2147483647")) {
      var item = new ProductCursor("COLLECTION:summer", value, ID, "");
      assertThat(ProductCursor.decode(item.encode(), "COLLECTION:summer", "")).isEqualTo(item);
      assertThrows(Api.Problem.class, () -> ProductCursor.decode(item.encode(), "COLLECTION:winter", ""));
    }
    for (var value : List.of("-1", "2147483648", "2026-10-11T00:00:00Z")) {
      assertThrows(Api.Problem.class, () -> ProductCursor.decode(
          new ProductCursor("COLLECTION:summer", value, ID, "").encode(), "COLLECTION:summer", ""));
    }
    assertThrows(Api.Problem.class, () -> ProductCursor.decode(
        new ProductCursor("COLLECTIONS", "abc", ID, "").encode(), "COLLECTIONS", ""));
    assertThrows(Api.Problem.class, () -> ProductCursor.decode(list.encode(), "COLLECTIONS", "other"));
    assertThrows(Api.Problem.class, () -> ProductCursor.decode(
        new ProductCursor("UNKNOWN", "0", ID, "").encode(), "UNKNOWN", ""));
  }

  @Test void timestampCursorRejectsUtcYearsOutsideDatabaseDomain() {
    var filters = BrowseFilters.parse(Map.of(), false);
    for (var value : List.of("+300000-01-01T00:00:00Z", "+1000000000-01-01T00:00:00Z",
        "0000-12-31T23:59:59Z", "+10000-01-01T00:00:00Z")) {
      assertCursorError(new ProductCursor("NEWEST", value, ID, filters.hash()).encode(), filters);
      var error = assertThrows(Api.Problem.class, () -> ProductCursor.decode(
          new ProductCursor("COLLECTIONS", value, ID, "").encode(), "COLLECTIONS", ""));
      assertThat(error.getMessage()).isEqualTo("INVALID_CURSOR");
      assertThat(error.errors()).extracting(Api.FieldError::field).containsExactly("cursor");
    }
  }

  @Test void timestampCursorAcceptsUtcYearBoundaries() {
    var filters = BrowseFilters.parse(Map.of(), false);
    for (var value : List.of("0001-01-01T00:00:00Z", "9999-12-31T23:59:59.999999Z")) {
      var newest = new ProductCursor("NEWEST", value, ID, filters.hash());
      assertThat(ProductCursor.decode(newest.encode(), filters)).isEqualTo(newest);
      var collections = new ProductCursor("COLLECTIONS", value, ID, "");
      assertThat(ProductCursor.decode(collections.encode(), "COLLECTIONS", "")).isEqualTo(collections);
    }
  }

  private static Api.Problem filterError(Map<String, String> params, boolean requireQ) {
    return assertThrows(Api.Problem.class, () -> BrowseFilters.parse(params, requireQ));
  }

  private static List<String> fields(Map<String, String> params, boolean requireQ) {
    return filterError(params, requireQ).errors().stream().map(Api.FieldError::field).toList();
  }

  private static void assertCursorError(String raw, BrowseFilters filters) {
    var e = assertThrows(Api.Problem.class, () -> ProductCursor.decode(raw, filters));
    assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(e.code()).isEqualTo("VALIDATION_ERROR");
    assertThat(e.getMessage()).isEqualTo("INVALID_CURSOR");
    assertThat(e.errors()).extracting(Api.FieldError::field).containsExactly("cursor");
  }

  private static String raw(String json) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
  }
}
