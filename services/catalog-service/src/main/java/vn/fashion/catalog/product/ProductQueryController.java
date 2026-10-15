package vn.fashion.catalog.product;

import io.micrometer.tracing.Tracer;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.fashion.catalog.web.Api;
import vn.fashion.catalog.web.PublicLimit;

@RestController
@RequestMapping("/api/v1/catalog")
public class ProductQueryController {
  private final ProductBrowseRepository products;
  private final Tracer tracer;

  public ProductQueryController(ProductBrowseRepository products, Tracer tracer) {
    this.products = products;
    this.tracer = tracer;
  }

  @GetMapping("/products")
  public ResponseEntity<Api.Response<ProductPage>> listProducts(@RequestParam Map<String, String> params) {
    return browse(params, false);
  }

  @GetMapping("/search")
  public ResponseEntity<Api.Response<ProductPage>> search(@RequestParam Map<String, String> params) {
    return browse(params, true);
  }

  private ResponseEntity<Api.Response<ProductPage>> browse(Map<String, String> params, boolean requireQ) {
    int limit = PublicLimit.parse(params.get("limit"));
    BrowseFilters filters = BrowseFilters.parse(params, requireQ);
    ProductCursor after = params.containsKey("cursor") ? ProductCursor.decode(params.get("cursor"), filters) : null;
    return Api.ok(HttpStatus.OK, products.browse(filters, after, limit), tracer);
  }
}
