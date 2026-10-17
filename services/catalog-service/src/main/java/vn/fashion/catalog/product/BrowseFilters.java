package vn.fashion.catalog.product;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import vn.fashion.catalog.web.Api;

public record BrowseFilters(String q, UUID categoryId, UUID brandId, String size, String color,
                            String tag, Long minPrice, Long maxPrice, Sort sort) {
  public enum Sort { NEWEST, BEST_SELLING, PRICE_ASC, PRICE_DESC }

  public static BrowseFilters parse(Map<String, String> params, boolean requireQ) {
    var errors = new ArrayList<Api.FieldError>();
    String q = text(params.get("q"), "q", 100, errors);
    if (requireQ && q == null) errors.add(new Api.FieldError("q", "is required"));
    UUID category = uuid(params.get("category_id"), "category_id", errors);
    UUID brand = uuid(params.get("brand_id"), "brand_id", errors);
    String size = text(params.get("size"), "size", 64, errors);
    String color = text(params.get("color"), "color", 64, errors);
    String tag = text(params.get("tag"), "tag", 64, errors);
    Long min = price(params.get("min_price"), "min_price", errors);
    Long max = price(params.get("max_price"), "max_price", errors);
    if (min != null && max != null && min > max) {
      errors.add(new Api.FieldError("max_price", "must be at least min_price"));
    }
    Sort sort = Sort.NEWEST;
    try {
      if (params.get("sort") != null) sort = Sort.valueOf(params.get("sort"));
    } catch (IllegalArgumentException e) {
      errors.add(new Api.FieldError("sort", "must be NEWEST, BEST_SELLING, PRICE_ASC or PRICE_DESC"));
    }
    if (!errors.isEmpty()) {
      throw new Api.Problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "INVALID_FILTER", errors);
    }
    return new BrowseFilters(q, category, brand, size, color, tag, min, max, sort);
  }

  /** Exact validated values for the cache key; unlike hash(), case is kept because size/color/tag match exactly. */
  public String cacheKey() {
    return String.join("|", sort.name(), canonical(q), canonical(categoryId), canonical(brandId), canonical(size),
        canonical(color), canonical(tag), canonical(minPrice), canonical(maxPrice));
  }

  public String hash() {
    String fields = String.join("|", sort.name(), canonical(lower(q)), canonical(categoryId),
        canonical(brandId), canonical(lower(size)), canonical(lower(color)), canonical(tag),
        canonical(minPrice), canonical(maxPrice));
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(fields.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String lower(String value) {
    return value == null ? null : value.toLowerCase(Locale.ROOT);
  }

  private static String canonical(Object value) {
    return value == null ? "" : value.toString().replace("\\", "\\\\").replace("|", "\\|");
  }

  private static String text(String raw, String field, int max, List<Api.FieldError> errors) {
    if (raw == null) return null;
    String value = raw.trim();
    if (value.isEmpty() || value.length() > max) {
      errors.add(new Api.FieldError(field, "must be 1.." + max + " characters"));
    }
    return value;
  }

  private static UUID uuid(String value, String field, List<Api.FieldError> errors) {
    if (value == null) return null;
    try {
      UUID id = UUID.fromString(value);
      if (!id.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
      return id;
    } catch (IllegalArgumentException e) {
      errors.add(new Api.FieldError(field, "must be a UUID"));
      return null;
    }
  }

  private static Long price(String value, String field, List<Api.FieldError> errors) {
    if (value == null) return null;
    try {
      long price = Long.parseLong(value);
      if (price < 0) throw new IllegalArgumentException();
      return price;
    } catch (IllegalArgumentException e) {
      errors.add(new Api.FieldError(field, "must be a nonnegative integer VND"));
      return null;
    }
  }
}
