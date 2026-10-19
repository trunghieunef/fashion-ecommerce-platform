package vn.fashion.shipping.rules;

import io.micrometer.tracing.Tracer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.fashion.platform.idempotency.IdempotencyStore;
import vn.fashion.shipping.web.AdminAuth;
import vn.fashion.shipping.web.Api;

/** OPS fee rules (03 section 3, permission shipping.rules): versioned writes, each one audited with actor and reason. */
@RestController
@RequestMapping("/admin/api/v1/shipping/rules")
public class RulesController {
  public static final String PERMISSION = "shipping.rules";
  record Write(String name, String ruleType, int priority, JsonNode config, boolean active, String reason, long expectedVersion) { }
  private final AdminAuth auth;
  private final RuleRepository rules;
  private final TransactionTemplate tx;
  private final ObjectMapper json;
  private final Tracer tracer;
  private final IdempotencyStore keys;

  public RulesController(AdminAuth auth, RuleRepository rules, TransactionTemplate tx, ObjectMapper json, Tracer tracer,
                         IdempotencyStore keys) {
    this.auth = auth; this.rules = rules; this.tx = tx; this.json = json; this.tracer = tracer; this.keys = keys;
  }

  @GetMapping
  public ResponseEntity<?> list(@RequestHeader(name = "Authorization", required = false) String authorization) {
    auth.require(authorization, PERMISSION);
    return Api.ok(HttpStatus.OK, rules.all(), tracer);
  }

  @PostMapping
  public ResponseEntity<?> create(@RequestHeader(name = "Authorization", required = false) String authorization,
                                  @RequestHeader(name = "Idempotency-Key", required = false) String key,
                                  @RequestBody(required = false) String body) {
    UUID actor = auth.require(authorization, PERMISSION);
    Api.requireKey(key);
    var w = parse(Api.object(json, body), false);
    // Review I2 (03 section 1.3): an OPS retry after a timeout replays the first create instead of adding a rule.
    String actorKey = "user:" + actor, operation = "shipping.rule.create";
    String hash = Api.sha256(json.writeValueAsString(w));
    var stored = keys.find(actorKey, operation, key, hash).map(o -> Api.replay(o, json));
    if (stored.isPresent()) return Api.ok(HttpStatus.CREATED, stored.get(), tracer);
    UUID id = UUID.randomUUID();
    var created = tx.execute(status -> {
      var replay = Api.replay(keys.begin(actorKey, operation, key, hash), json);
      if (replay != null) return replay;
      rules.insert(id, w.name(), w.ruleType(), w.priority(), w.config(), w.active());
      var rule = rules.get(id);
      rules.audit(rule, actor, w.reason());
      keys.finish(actorKey, operation, key, id, 201, json.writeValueAsString(rule));
      return rule;
    });
    return Api.ok(HttpStatus.CREATED, created, tracer);
  }

  @PutMapping("/{id}")
  public ResponseEntity<?> update(@RequestHeader(name = "Authorization", required = false) String authorization,
                                  @PathVariable String id, @RequestBody(required = false) String body) {
    UUID actor = auth.require(authorization, PERMISSION);
    UUID ruleId = uuid(id);
    var w = parse(Api.object(json, body), true);
    var updated = tx.execute(status -> {
      var current = rules.lock(ruleId).orElseThrow(RulesController::notFound);
      if (current.version() != w.expectedVersion())
        throw new Api.Problem(HttpStatus.CONFLICT, "VERSION_CONFLICT", "VERSION_CONFLICT",
            List.of(new Api.FieldError("expected_version", "current=" + current.version())));
      rules.update(ruleId, w.name(), w.ruleType(), w.priority(), w.config(), w.active());
      var rule = rules.get(ruleId);
      rules.audit(rule, actor, w.reason());
      return rule;
    });
    return Api.ok(HttpStatus.OK, updated, tracer);
  }

  private Write parse(JsonNode body, boolean update) {
    var errors = new ArrayList<Api.FieldError>();
    String name = text(body.path("name"), "name", 200, errors);
    String reason = text(body.path("reason"), "reason", 500, errors);
    JsonNode p = body.path("priority");
    int priority = p.isIntegralNumber() && p.canConvertToInt() && p.intValue() >= 0 ? p.intValue() : -1;
    if (priority < 0) errors.add(new Api.FieldError("priority", "must be an integer >= 0"));
    JsonNode a = body.path("active");
    // Review 3: a PUT must state active, so editing a disabled rule never re-enables it by omission.
    if ((update && a.isMissingNode()) || (!a.isMissingNode() && !a.isBoolean()))
      errors.add(new Api.FieldError("active", update ? "is required, a boolean" : "must be a boolean"));
    long expected = -1;
    if (update) {
      JsonNode v = body.path("expected_version");
      if (v.isIntegralNumber() && v.canConvertToLong() && v.longValue() >= 0) expected = v.longValue();
      else errors.add(new Api.FieldError("expected_version", "is required, a non-negative integer"));
    }
    String type = body.path("rule_type").isString() ? body.path("rule_type").asString() : null;
    if (!errors.isEmpty()) throw RuleConfig.invalid(errors);
    RuleConfig.parse(type, body.get("config"));
    return new Write(name, type, priority, body.get("config"), a.isMissingNode() || a.asBoolean(), reason, expected);
  }

  private static String text(JsonNode node, String field, int max, List<Api.FieldError> errors) {
    String value = node.isString() ? node.asString().strip() : "";
    if (value.isEmpty() || value.length() > max) { errors.add(new Api.FieldError(field, "must be 1.." + max + " characters")); return null; }
    return value;
  }
  private static UUID uuid(String raw) {
    UUID id = Api.uuid(raw);
    if (id == null) throw notFound();
    return id;
  }
  static Api.Problem notFound() { return new Api.Problem(HttpStatus.NOT_FOUND, "NOT_FOUND", "RULE_NOT_FOUND", List.of()); }
}
