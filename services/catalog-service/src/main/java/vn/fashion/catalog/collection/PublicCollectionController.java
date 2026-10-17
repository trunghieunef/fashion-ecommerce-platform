package vn.fashion.catalog.collection;

import io.micrometer.tracing.Tracer;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.fashion.catalog.product.ProductCursor;
import jakarta.servlet.http.HttpServletResponse;
import vn.fashion.catalog.web.Api;
import vn.fashion.catalog.web.PublicCacheHeaders;
import vn.fashion.catalog.web.PublicLimit;

@RestController
@RequestMapping("/api/v1/catalog/collections")
public class PublicCollectionController {
  private final PublicCollectionRepository collections;
  private final Tracer tracer;

  public PublicCollectionController(PublicCollectionRepository collections, Tracer tracer) {
    this.collections = collections; this.tracer = tracer;
  }

  @GetMapping
  public ResponseEntity<Api.Response<PublicCollectionRepository.CollectionPage>> list(
      @RequestParam Map<String, String> params, HttpServletResponse response) {
    int size = PublicLimit.parse(params.get("limit"));
    String cursor = params.get("cursor");
    ProductCursor after = cursor == null ? null : ProductCursor.decode(cursor, "COLLECTIONS", "");
    var page = collections.list(after, size);
    PublicCacheHeaders.allow(response, PublicCacheHeaders.LIST);
    return Api.ok(HttpStatus.OK, page, tracer);
  }

  @GetMapping("/{slug}")
  public ResponseEntity<Api.Response<PublicCollectionRepository.CollectionDetail>> detail(@PathVariable String slug,
      @RequestParam Map<String, String> params, HttpServletResponse response) {
    int size = PublicLimit.parse(params.get("limit"));
    String cursor = params.get("cursor");
    ProductCursor after = cursor == null ? null : ProductCursor.decode(cursor, "COLLECTION:" + slug, "");
    var detail = collections.detail(slug, after, size).orElseThrow(() ->
        new Api.Problem(HttpStatus.NOT_FOUND, "NOT_FOUND", "COLLECTION_NOT_FOUND", List.of()));
    PublicCacheHeaders.allow(response, PublicCacheHeaders.STATIC);
    return Api.ok(HttpStatus.OK, detail, tracer);
  }

}
