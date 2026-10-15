package vn.fashion.catalog.web;

import java.util.List;
import org.springframework.http.HttpStatus;

public final class PublicLimit {
  private PublicLimit() { }

  public static int parse(String raw) {
    if (raw == null) return 20;
    try {
      int value = Integer.parseInt(raw);
      if (value >= 1 && value <= 100) return value;
    } catch (NumberFormatException ignored) { }
    throw new Api.Problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "INVALID_LIMIT",
        List.of(new Api.FieldError("limit", "must be between 1 and 100")));
  }
}
