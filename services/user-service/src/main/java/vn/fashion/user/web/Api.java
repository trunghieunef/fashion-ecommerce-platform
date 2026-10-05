package vn.fashion.user.web;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Response/error envelope of 03 section 1.2 shared by this service's controllers. */
public final class Api {
  public record Metadata(String requestId, String traceId) {
  }

  public record Response<T>(String code, T data, Metadata metadata) {
  }

  public record FieldError(String field, String message) {
  }

  public record Error(String code, String message, List<FieldError> errors, Metadata metadata) {
  }

  /** A 4xx outcome a controller raises; {@link ApiExceptionHandler} renders it. */
  public static class Problem extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final List<FieldError> errors;

    public Problem(HttpStatus status, String code, String message, List<FieldError> errors) {
      super(message, null, false, false);
      this.status = status;
      this.code = code;
      this.errors = List.copyOf(errors);
    }

    public HttpStatus status() {
      return status;
    }

    public String code() {
      return code;
    }

    public List<FieldError> errors() {
      return errors;
    }
  }

  private Api() {
  }

  /** trace_id is the request's W3C trace; request_id is new per response. */
  public static Metadata metadata(Tracer tracer) {
    Span span = tracer.currentSpan();
    String traceId = span != null ? span.context().traceId() : UUID.randomUUID().toString().replace("-", "");
    return new Metadata(UUID.randomUUID().toString(), traceId);
  }

  public static <T> ResponseEntity<Response<T>> ok(HttpStatus status, T data, Tracer tracer) {
    Metadata metadata = metadata(tracer);
    return ResponseEntity.status(status).header("X-Correlation-Id", metadata.traceId())
        .body(new Response<>("OK", data, metadata));
  }

  public static ResponseEntity<Error> error(HttpStatus status, String code, String message,
                                           List<FieldError> errors, Tracer tracer) {
    Metadata metadata = metadata(tracer);
    return ResponseEntity.status(status).header("X-Correlation-Id", metadata.traceId())
        .body(new Error(code, message, errors, metadata));
  }
}
