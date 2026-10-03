package vn.fashion.catalog.product;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/catalog/products")
public class ProductQueryController {
  private final ProductQueryRepository products;

  public ProductQueryController(ProductQueryRepository products) {
    this.products = products;
  }

  @GetMapping
  public ResponseEntity<ApiResponse<ProductPage>> listProducts(
      @RequestParam(defaultValue = "20") int limit) {
    if (limit < 1 || limit > 100) {
      throw new InvalidLimitException(limit);
    }
    ResponseMetadata metadata = metadata();
    return ResponseEntity.ok()
        .header("X-Correlation-Id", metadata.traceId())
        .body(new ApiResponse<>("OK", products.findActive(limit), metadata));
  }

  @ExceptionHandler({InvalidLimitException.class, ResponseStatusException.class,
      MethodArgumentTypeMismatchException.class})
  public ResponseEntity<ApiError> invalidRequest(Exception exception) {
    ResponseMetadata metadata = metadata();
    String message = exception instanceof ResponseStatusException statusException
        ? statusException.getReason()
        : "INVALID_LIMIT";
    Object rejectedValue = exception instanceof InvalidLimitException invalidLimitException
        ? invalidLimitException.rejectedValue()
        : exception instanceof MethodArgumentTypeMismatchException mismatchException
            ? mismatchException.getValue()
            : null;
    return ResponseEntity.badRequest()
        .header("X-Correlation-Id", metadata.traceId())
        .body(new ApiError(
            "VALIDATION_ERROR",
            message,
            List.of(new ApiFieldError("limit", "must be between 1 and 100", rejectedValue)),
            metadata));
  }

  private ResponseMetadata metadata() {
    return new ResponseMetadata(UUID.randomUUID().toString(), UUID.randomUUID().toString());
  }

  public record ApiResponse<T>(String code, T data, ResponseMetadata metadata) {
  }

  public record ApiError(String code, String message, List<ApiFieldError> errors,
                         ResponseMetadata metadata) {
  }

  public record ApiFieldError(String field, String message,
                              @JsonProperty("rejected_value") Object rejectedValue) {
  }

  public record ResponseMetadata(@JsonProperty("request_id") String requestId,
                                 @JsonProperty("trace_id") String traceId) {
  }

  private static final class InvalidLimitException extends RuntimeException {
    private final int rejectedValue;

    private InvalidLimitException(int rejectedValue) {
      this.rejectedValue = rejectedValue;
    }

    private int rejectedValue() {
      return rejectedValue;
    }
  }
}
