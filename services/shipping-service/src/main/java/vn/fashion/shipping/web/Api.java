package vn.fashion.shipping.web;

import io.micrometer.tracing.Tracer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.fashion.platform.idempotency.IdempotencyStore;

/** Response envelope (03 section 1.2), matching user-service. */
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
    return ResponseEntity.status(status).header("X-Correlation-Id", meta.traceId())
        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(new Error(code, message, errors, meta));
  }

  /** Request body that must be a JSON object, else 400 MALFORMED_JSON. */
  public static JsonNode object(ObjectMapper json, String body) {
    try {
      JsonNode node = body == null ? null : json.readTree(body);
      if (node != null && node.isObject()) return node;
    } catch (tools.jackson.core.JacksonException ignored) { }
    throw new Problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "MALFORMED_JSON", List.of());
  }
  /** Lowercase canonical UUID, else null. */
  public static UUID uuid(String raw) {
    try { UUID id = UUID.fromString(raw); if (id.toString().equals(raw)) return id; } catch (RuntimeException ignored) { }
    return null;
  }
  public static void requireKey(String key) {
    if (key == null || key.isBlank() || key.length() > 128)
      throw new Problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "INVALID_KEY", List.of(new FieldError("Idempotency-Key", "must be 1..128 characters")));
  }
  public static String sha256(String value) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
    catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
  }
  /**
   * Idempotency outcome: CONFLICT -> 409, COMPLETED -> the stored body (a stored 4xx is thrown again), otherwise null
   * (the caller does the work).
   */
  public static Object replay(IdempotencyStore.Outcome outcome, ObjectMapper json) {
    if (outcome.status() == IdempotencyStore.Status.CONFLICT)
      throw new Problem(HttpStatus.CONFLICT, "CONFLICT", "IDEMPOTENCY_KEY_REUSED", List.of());
    if (outcome.status() != IdempotencyStore.Status.COMPLETED) return null;
    JsonNode body = json.readTree(outcome.responseBody());
    if (outcome.responseCode() >= 400) {
      var errors = new java.util.ArrayList<FieldError>();
      body.path("errors").forEach(e -> errors.add(new FieldError(e.path("field").asString(), e.path("message").asString())));
      throw new Problem(HttpStatus.valueOf(outcome.responseCode()), body.path("code").asString(), body.path("message").asString(), errors);
    }
    return body;
  }
  /** Stored form of a rejected request, replayed by {@link #replay}. */
  public static java.util.Map<String, Object> body(Problem p) {
    return java.util.Map.of("code", p.code(), "message", p.getMessage(), "errors", p.errors());
  }
}
