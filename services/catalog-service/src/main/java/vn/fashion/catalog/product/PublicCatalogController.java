package vn.fashion.catalog.product;

import io.micrometer.tracing.Tracer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.fashion.catalog.stock.StockObservations;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import tools.jackson.core.type.TypeReference;
import vn.fashion.catalog.cache.PublicCache;
import vn.fashion.catalog.web.Api;
import vn.fashion.catalog.web.PublicCacheHeaders;

@RestController
@RequestMapping("/api/v1/catalog")
public class PublicCatalogController {
  private final PublicCatalogRepository catalog;
  private static final Duration TTL = Duration.ofSeconds(300);
  private final StockObservations stock;
  private final PublicCache cache;
  private final Tracer tracer;

  public PublicCatalogController(PublicCatalogRepository catalog, StockObservations stock, PublicCache cache, Tracer tracer) {
    this.catalog = catalog; this.stock = stock; this.cache = cache; this.tracer = tracer;
  }

  @GetMapping("/products/{id}")
  public ResponseEntity<Api.Response<PublicCatalogRepository.ProductDetail>> product(@PathVariable String id,
      @RequestHeader(value = "Accept-Language", defaultValue = "") String language, HttpServletResponse response) {
    UUID product = publicId(id, "PRODUCT_NOT_FOUND");
    String locale = locale(language);
    // Stock is attached after the cache: it is never cached (spec section 4).
    var detail = cache.read("product", TTL, new TypeReference<PublicCatalogRepository.ProductDetail>() { },
        () -> catalog.product(product, locale), product.toString(), locale).orElseThrow(() -> notFound("PRODUCT_NOT_FOUND"));
    PublicCacheHeaders.allow(response, PublicCacheHeaders.DETAIL);
    PublicCacheHeaders.varyLanguage(response);
    return Api.ok(HttpStatus.OK, stock.attach(detail), tracer);
  }

  @GetMapping("/categories")
  public ResponseEntity<Api.Response<List<PublicCatalogRepository.CategoryNode>>> categories(HttpServletResponse response) {
    PublicCacheHeaders.allow(response, PublicCacheHeaders.LIST);
    return Api.ok(HttpStatus.OK, cache.read("categories", TTL, new TypeReference<List<PublicCatalogRepository.CategoryNode>>() { },
        () -> Optional.of(catalog.categories())).orElseThrow(), tracer);
  }

  @GetMapping("/brands")
  public ResponseEntity<Api.Response<List<PublicCatalogRepository.BrandView>>> brands(HttpServletResponse response) {
    PublicCacheHeaders.allow(response, PublicCacheHeaders.LIST);
    return Api.ok(HttpStatus.OK, cache.read("brands", TTL, new TypeReference<List<PublicCatalogRepository.BrandView>>() { },
        () -> Optional.of(catalog.brands())).orElseThrow(), tracer);
  }

  @GetMapping("/size-guides/{categoryId}")
  public ResponseEntity<Api.Response<PublicCatalogRepository.SizeGuideView>> sizeGuide(@PathVariable String categoryId,
      @RequestParam(required = false) String locale,
      @RequestHeader(value = "Accept-Language", defaultValue = "") String language, HttpServletResponse response) {
    UUID category = publicId(categoryId, "SIZE_GUIDE_NOT_FOUND");
    String selected = locale == null ? locale(language) : locale;
    if (!"vi".equals(selected) && !"en".equals(selected)) {
      throw new Api.Problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "INVALID_FIELDS",
          List.of(new Api.FieldError("locale", "must be vi or en")));
    }
    var guide = cache.read("size-guide", TTL, new TypeReference<PublicCatalogRepository.SizeGuideView>() { },
        () -> catalog.sizeGuide(category, selected), category.toString(), selected).orElseThrow(() -> notFound("SIZE_GUIDE_NOT_FOUND"));
    PublicCacheHeaders.allow(response, PublicCacheHeaders.STATIC);
    PublicCacheHeaders.varyLanguage(response);
    return Api.ok(HttpStatus.OK, guide, tracer);
  }

  private static String locale(String language) { return language.toLowerCase(Locale.ROOT).startsWith("en") ? "en" : "vi"; }

  private static UUID publicId(String id, String message) {
    try {
      UUID parsed = UUID.fromString(id);
      if (!parsed.toString().equalsIgnoreCase(id)) throw notFound(message);
      return parsed;
    } catch (IllegalArgumentException e) { throw notFound(message); }
  }

  private static Api.Problem notFound(String message) {
    return new Api.Problem(HttpStatus.NOT_FOUND, "NOT_FOUND", message, List.of());
  }
}
