package vn.fashion.catalog.web;

import io.micrometer.tracing.Tracer;
import java.util.List;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(basePackages = "vn.fashion.catalog.admin")
public class ApiExceptionHandler {
  private final Tracer tracer;
  public ApiExceptionHandler(Tracer tracer) { this.tracer = tracer; }
  @ExceptionHandler(Api.Problem.class) ResponseEntity<Api.Error> problem(Api.Problem e) {
    return Api.error(e.status(), e.code(), e.getMessage(), e.errors(), tracer);
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
    return Api.error(HttpStatus.SERVICE_UNAVAILABLE, "TEMPORARILY_UNAVAILABLE", "DEPENDENCY_UNAVAILABLE", List.of(), tracer);
  }
  @ExceptionHandler(HttpMessageNotReadableException.class) ResponseEntity<Api.Error> unreadable() {
    return Api.error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "MALFORMED_JSON", List.of(), tracer);
  }
  @ExceptionHandler(MethodArgumentTypeMismatchException.class) ResponseEntity<Api.Error> mismatch(MethodArgumentTypeMismatchException e) {
    return Api.error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "INVALID_PARAMETER", List.of(new Api.FieldError(e.getName(), "has an invalid format")), tracer);
  }
}
