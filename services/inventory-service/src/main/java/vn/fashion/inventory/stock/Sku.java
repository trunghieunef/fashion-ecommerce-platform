package vn.fashion.inventory.stock;

import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import vn.fashion.inventory.web.Api;

public final class Sku {
  private Sku() { }
  public static boolean valid(String canonical) {
    return canonical != null && canonical.matches("[A-Z0-9][A-Z0-9._-]{0,63}");
  }
  public static String canonical(String raw) {
    if(raw != null && raw.strip().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))
      return raw.strip().toUpperCase(Locale.ROOT);
    throw new Api.Problem(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","INVALID_SKU",
        List.of(new Api.FieldError("sku","must be 1..64 ASCII SKU characters")));
  }
}
