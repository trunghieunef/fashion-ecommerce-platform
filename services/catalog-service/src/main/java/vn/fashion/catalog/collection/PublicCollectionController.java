package vn.fashion.catalog.collection;

import io.micrometer.tracing.Tracer;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.fashion.catalog.product.ProductCursor;
import vn.fashion.catalog.web.Api;

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
      @RequestParam(required = false) String limit, @RequestParam(required = false) String cursor) {
    int size = limit(limit);
    ProductCursor after = cursor == null ? null : ProductCursor.decode(cursor, "COLLECTIONS", "");
    return Api.ok(HttpStatus.OK, collections.list(after, size), tracer);
  }

  @GetMapping("/{slug}")
  public ResponseEntity<Api.Response<PublicCollectionRepository.CollectionDetail>> detail(@PathVariable String slug,
      @RequestParam(required = false) String limit, @RequestParam(required = false) String cursor) {
    int size = limit(limit);
    ProductCursor after = cursor == null ? null : ProductCursor.decode(cursor, "COLLECTION:" + slug, "");
    return Api.ok(HttpStatus.OK, collections.detail(slug, after, size).orElseThrow(() ->
        new Api.Problem(HttpStatus.NOT_FOUND, "NOT_FOUND", "COLLECTION_NOT_FOUND", List.of())), tracer);
  }

  private static int limit(String raw) {
    if (raw == null) return 20;
    try {
      int value = Integer.parseInt(raw);
      if (value >= 1 && value <= 100) return value;
    } catch (NumberFormatException ignored) {}
    throw new Api.Problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "INVALID_LIMIT",
        List.of(new Api.FieldError("limit", "must be between 1 and 100")));
  }
}
