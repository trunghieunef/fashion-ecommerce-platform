package vn.fashion.catalog.web;

import io.micrometer.tracing.Tracer;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Catalog admin response envelope (03 section 1.2), matching user-service. */
public final class Api {
  public record Metadata(String requestId, String traceId) { }
  public record Response<T>(String code, T data, Metadata metadata) { }
  public record FieldError(String field, String message) { }
  public record Error(String code, String message, List<FieldError> errors, Metadata metadata) { }
  public static class Problem extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final List<FieldError> errors;
    public Problem(HttpStatus status, String code, String message, List<FieldError> errors) {
      super(message, null, false, false);
      this.status = status; this.code = code; this.errors = List.copyOf(errors);
    }
    public HttpStatus status() { return status; }
    public String code() { return code; }
    public List<FieldError> errors() { return errors; }
  }
  private Api() { }
  public static Metadata metadata(Tracer tracer) {
    var span = tracer.currentSpan();
    return new Metadata(UUID.randomUUID().toString(), span == null
        ? UUID.randomUUID().toString().replace("-", "") : span.context().traceId());
  }
  public static <T> ResponseEntity<Response<T>> ok(HttpStatus status, T data, Tracer tracer) {
    return ok(status, data, metadata(tracer));
  }
  public static <T> ResponseEntity<Response<T>> ok(HttpStatus status, T data, Metadata meta) {
    return ResponseEntity.status(status).header("X-Correlation-Id", meta.traceId()).body(new Response<>("OK", data, meta));
  }
  public static ResponseEntity<Error> error(HttpStatus status, String code, String message, List<FieldError> errors, Tracer tracer) {
    return error(status, code, message, errors, metadata(tracer));
  }
  public static ResponseEntity<Error> error(HttpStatus status, String code, String message, List<FieldError> errors, Metadata meta) {
    return ResponseEntity.status(status).header("X-Correlation-Id", meta.traceId()).body(new Error(code, message, errors, meta));
  }
}
