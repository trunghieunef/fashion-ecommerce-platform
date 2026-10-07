package vn.fashion.catalog.admin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.fashion.catalog.web.Api;
import vn.fashion.platform.idempotency.IdempotencyStore;

@Component
public class AdminCommands {
  public record Result(UUID resourceId, int status, Object data) { }
  public record Replay(int status, JsonNode data) { }
  private final TransactionTemplate tx;
  private final IdempotencyStore keys;
  private final ObjectMapper json;
  public AdminCommands(TransactionTemplate tx, IdempotencyStore keys, ObjectMapper json) {
    this.tx = tx; this.keys = keys; this.json = json;
  }
  public Replay create(UUID actor, String operation, String key, Object request, Supplier<Result> work) {
    if (key == null || key.isBlank() || key.length() > 128)
      throw new Api.Problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "INVALID_KEY", List.of(new Api.FieldError("Idempotency-Key", "must be 1..128 characters")));
    String hash;
    try { hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(request).getBytes(StandardCharsets.UTF_8))); }
    catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    return tx.execute(status -> {
      String actorKey = "user:" + actor;
      var outcome = keys.begin(actorKey, operation, key, hash);
      if (outcome.status() == IdempotencyStore.Status.CONFLICT)
        throw new Api.Problem(HttpStatus.CONFLICT, "CONFLICT", "IDEMPOTENCY_KEY_REUSED", List.of());
      if (outcome.status() == IdempotencyStore.Status.COMPLETED)
        return new Replay(outcome.responseCode(), json.readTree(outcome.responseBody()));
      if (outcome.status() != IdempotencyStore.Status.STARTED) throw new IllegalStateException("unexpected pending catalog command");
      var result = work.get();
      String body = json.writeValueAsString(result.data());
      keys.finish(actorKey, operation, key, result.resourceId(), result.status(), body);
      return new Replay(result.status(), json.readTree(body));
    });
  }
  public <T> T update(Supplier<T> work) { return tx.execute(status -> work.get()); }
  public static void requireVersion(Long expected, long actual) {
    if (expected == null) throw new Api.Problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "VERSION_REQUIRED", List.of(new Api.FieldError("expected_version", "is required")));
    if (expected != actual) throw new Api.Problem(HttpStatus.CONFLICT, "VERSION_CONFLICT", "STALE_VERSION", List.of());
  }
}
