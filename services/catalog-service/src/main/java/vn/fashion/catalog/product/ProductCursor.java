package vn.fashion.catalog.product;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.fashion.catalog.web.Api;

public record ProductCursor(@JsonProperty("s") String sort, @JsonProperty("v") String value,
                            @JsonProperty("id") UUID id, @JsonProperty("f") String filters) {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  public String encode() {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(MAPPER.writeValueAsBytes(this));
  }

  public static ProductCursor decode(String raw, BrowseFilters filters) {
    return decode(raw, filters.sort().name(), filters.hash());
  }

  public static ProductCursor decode(String raw, String expectedSort, String expectedFilters) {
    try {
      if (raw == null) throw new IllegalArgumentException();
      JsonNode json = MAPPER.readTree(Base64.getUrlDecoder().decode(raw));
      String sort = text(json, "s");
      String value = text(json, "v");
      String rawId = text(json, "id");
      String filters = text(json, "f");
      UUID id = UUID.fromString(rawId);
      if (!id.toString().equalsIgnoreCase(rawId) || !sort.equals(expectedSort)
          || !filters.equals(expectedFilters)) throw new IllegalArgumentException();
      if (sort.equals("NEWEST") || sort.equals("COLLECTIONS")) {
        int year = Instant.parse(value).atOffset(ZoneOffset.UTC).getYear();
        if (year < 1 || year > 9999) throw new IllegalArgumentException();
      } else if (sort.equals("BEST_SELLING") || sort.equals("PRICE_ASC") || sort.equals("PRICE_DESC")) {
        if (Long.parseLong(value) < 0) throw new IllegalArgumentException();
      } else if (sort.startsWith("COLLECTION:") && sort.length() > "COLLECTION:".length()) {
        if (Integer.parseInt(value) < 0) throw new IllegalArgumentException();
      } else {
        throw new IllegalArgumentException();
      }
      return new ProductCursor(sort, value, id, filters);
    } catch (IllegalArgumentException | DateTimeException | JacksonException e) {
      throw new Api.Problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "INVALID_CURSOR",
          List.of(new Api.FieldError("cursor", "has an invalid format or does not match filters")));
    }
  }

  private static String text(JsonNode json, String field) {
    JsonNode value = json == null ? null : json.get(field);
    if (value == null || !value.isTextual()) throw new IllegalArgumentException();
    return value.asText();
  }
}
