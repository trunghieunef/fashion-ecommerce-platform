package vn.fashion.catalog.product;

import io.micrometer.tracing.Tracer;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Optional;
import tools.jackson.core.type.TypeReference;
import vn.fashion.catalog.cache.PublicCache;
import vn.fashion.catalog.web.Api;
import vn.fashion.catalog.web.PublicCacheHeaders;
import vn.fashion.catalog.web.PublicLimit;

@RestController
@RequestMapping("/api/v1/catalog")
public class ProductQueryController {
  private final ProductBrowseRepository products;
  private final PublicCache cache;
  private final Tracer tracer;

  public ProductQueryController(ProductBrowseRepository products, PublicCache cache, Tracer tracer) {
    this.products = products;
    this.cache = cache;
    this.tracer = tracer;
  }

  @GetMapping("/products")
  public ResponseEntity<Api.Response<ProductPage>> listProducts(@RequestParam Map<String, String> params,
      HttpServletResponse response) {
    return browse(params, false, response);
  }

  @GetMapping("/search")
  public ResponseEntity<Api.Response<ProductPage>> search(@RequestParam Map<String, String> params,
      HttpServletResponse response) {
    return browse(params, true, response);
  }

  private ResponseEntity<Api.Response<ProductPage>> browse(Map<String, String> params, boolean requireQ,
      HttpServletResponse response) {
    int limit = PublicLimit.parse(params.get("limit"));
    BrowseFilters filters = BrowseFilters.parse(params, requireQ);
    ProductCursor after = params.containsKey("cursor") ? ProductCursor.decode(params.get("cursor"), filters) : null;
    var page = cache.read(requireQ ? "search" : "products", Duration.ofSeconds(30), new TypeReference<ProductPage>() { },
        () -> Optional.of(products.browse(filters, after, limit)), filters.cacheKey(), params.getOrDefault("cursor", ""),
        String.valueOf(limit)).orElseThrow();
    PublicCacheHeaders.allow(response, PublicCacheHeaders.LIST);
    return Api.ok(HttpStatus.OK, page, tracer);
  }
}
