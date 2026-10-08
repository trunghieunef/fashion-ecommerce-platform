package vn.fashion.catalog.web;

import io.micrometer.tracing.Tracer;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(basePackages = {"vn.fashion.catalog.admin", "vn.fashion.catalog.media"})
public class ApiExceptionHandler {
  private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);
  private final Tracer tracer;
  public ApiExceptionHandler(Tracer tracer) { this.tracer = tracer; }
  /** Explicit JSON type so a browser's Accept: image/* cannot turn an error into 406. */
  private static ResponseEntity<Api.Error> json(ResponseEntity<Api.Error> r) {
    return ResponseEntity.status(r.getStatusCode()).headers(r.getHeaders()).contentType(MediaType.APPLICATION_JSON).body(r.getBody());
  }
  @ExceptionHandler(Api.Problem.class) ResponseEntity<Api.Error> problem(Api.Problem e) {
    return json(Api.error(e.status(), e.code(), e.getMessage(), e.errors(), tracer));
  }
  @ExceptionHandler(DuplicateKeyException.class) ResponseEntity<Api.Error> duplicate(DuplicateKeyException e) {
    String constraint = e.getMostSpecificCause().getMessage();
    String field = constraint.contains("_slug_key") ? "slug" : constraint.contains("product_variants_sku_key")
        ? "sku" : constraint.contains("product_variants_product_size_color_key") ? "size" : "resource";
    return Api.error(HttpStatus.CONFLICT, "CONFLICT", "DUPLICATE", List.of(new Api.FieldError(field, "already exists")), tracer);
  }
  @ExceptionHandler(MissingRequestHeaderException.class) ResponseEntity<Api.Error> missingHeader(MissingRequestHeaderException e) {
    return Api.error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "MISSING_HEADER", List.of(new Api.FieldError(e.getHeaderName(), "is required")), tracer);
  }
  @ExceptionHandler({DataAccessResourceFailureException.class, QueryTimeoutException.class}) ResponseEntity<Api.Error> unavailable() {
    return json(Api.error(HttpStatus.SERVICE_UNAVAILABLE, "TEMPORARILY_UNAVAILABLE", "DEPENDENCY_UNAVAILABLE", List.of(), tracer));
  }
  @ExceptionHandler(vn.fashion.catalog.media.MediaStorage.Unavailable.class) ResponseEntity<Api.Error> storageUnavailable() {
    return json(Api.error(HttpStatus.SERVICE_UNAVAILABLE, "TEMPORARILY_UNAVAILABLE", "DEPENDENCY_UNAVAILABLE", List.of(), tracer));
  }
  @ExceptionHandler(HttpMessageNotReadableException.class) ResponseEntity<Api.Error> unreadable() {
    return Api.error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "MALFORMED_JSON", List.of(), tracer);
  }
  @ExceptionHandler(MethodArgumentTypeMismatchException.class) ResponseEntity<Api.Error> mismatch(MethodArgumentTypeMismatchException e) {
    return Api.error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "INVALID_PARAMETER", List.of(new Api.FieldError(e.getName(), "has an invalid format")), tracer);
  }
  @ExceptionHandler(Exception.class) ResponseEntity<?> internal(Exception e) {
    if (e instanceof ErrorResponse error && error.getStatusCode().is4xxClientError()) {
      var response = Api.error(HttpStatus.valueOf(error.getStatusCode().value()), "VALIDATION_ERROR", "INVALID_HTTP_REQUEST", List.of(), tracer);
      return ResponseEntity.status(error.getStatusCode()).headers(error.getHeaders()).headers(response.getHeaders())
          .contentType(MediaType.APPLICATION_JSON).body(response.getBody());
    }
    LOG.error("Catalog admin request failed", e);
    var meta = Api.metadata(tracer);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).header("X-Correlation-Id", meta.traceId())
        .body(Map.of("code", "INTERNAL", "message", "INTERNAL_ERROR", "metadata", meta));
  }
}
