package vn.fashion.payment.payments;

import io.micrometer.tracing.Tracer;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.fashion.payment.internal.InternalCallers;
import vn.fashion.payment.web.Api;

/** Internal payment API for order-service (03 section 4): callers, then key, then body. */
@RestController
@RequestMapping("/internal/api/v1/payments")
public class PaymentController {
  private final PaymentService payments;
  private final InternalCallers callers;
  private final ObjectMapper json;
  private final Tracer tracer;

  public PaymentController(PaymentService payments, InternalCallers callers, ObjectMapper json, Tracer tracer) {
    this.payments = payments; this.callers = callers; this.json = json; this.tracer = tracer;
  }

  @PostMapping
  public ResponseEntity<?> create(@RequestHeader(name = "Authorization", required = false) String authorization,
                                  @RequestHeader(name = "Idempotency-Key", required = false) String key,
                                  @RequestBody(required = false) String body) {
    callers.require(authorization);
    Api.requireKey(key);
    var meta = Api.metadata(tracer);
    var node = Api.object(json, body);
    var snapshot = PaymentService.parse(node, Set.of("client_ip", "locale"));
    return Api.ok(HttpStatus.OK, payments.create(key, snapshot, extras(node), meta.traceId()), meta);
  }

  /** TASK:PAY-02: client_ip (VNPAY needs it, checked by the service after the closed check: IPv4, or hex with ':'; 7..45 chars) and locale vi|en (default vi). */
  static PaymentService.Extras extras(JsonNode node) {
    var errors = new java.util.ArrayList<Api.FieldError>();
    JsonNode i = node.path("client_ip"), l = node.path("locale");
    String ip = i.isString() ? i.asString() : null;
    boolean present = !i.isMissingNode() && !i.isNull();
    if (present && (ip == null || ip.length() < 7 || ip.length() > 45
        || !(ip.matches("(\\d{1,3}\\.){3}\\d{1,3}") || ip.contains(":") && ip.matches("[0-9a-fA-F:.]+"))))
      errors.add(new Api.FieldError("client_ip", "must be an IPv4 or IPv6 address"));
    String locale = l.isString() ? l.asString() : null;
    if (!l.isMissingNode() && !l.isNull() && !Set.of("vi", "en").contains(locale)) errors.add(new Api.FieldError("locale", "must be vi or en"));
    if (!errors.isEmpty()) throw new Api.Problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "INVALID_FIELDS", errors);
    return new PaymentService.Extras(ip, locale == null ? "vi" : locale);
  }

  /** PAY-08: body is the original create snapshot plus reason 1..500. */
  @PostMapping("/by-order/{orderId}/close")
  public ResponseEntity<?> close(@RequestHeader(name = "Authorization", required = false) String authorization,
                                 @RequestHeader(name = "Idempotency-Key", required = false) String key,
                                 @PathVariable String orderId, @RequestBody(required = false) String body) {
    callers.require(authorization);
    Api.requireKey(key);
    UUID pathOrder = id(orderId);
    var node = Api.object(json, body);
    var snapshot = PaymentService.parse(node, Set.of("reason"));
    JsonNode r = node.path("reason");
    String reason = r.isString() ? r.asString().strip() : "";
    if (reason.isEmpty() || reason.length() > 500)
      throw new Api.Problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "INVALID_FIELDS", List.of(new Api.FieldError("reason", "must be 1..500 characters")));
    var meta = Api.metadata(tracer);
    return Api.ok(HttpStatus.OK, payments.close(key, pathOrder, snapshot, reason, meta.traceId()), meta);
  }

  @GetMapping("/by-order/{orderId}")
  public ResponseEntity<?> byOrder(@RequestHeader(name = "Authorization", required = false) String authorization,
                                   @PathVariable String orderId) {
    callers.require(authorization);
    return Api.ok(HttpStatus.OK, payments.byOrder(id(orderId)).orElseThrow(PaymentController::notFound), tracer);
  }

  static UUID id(String raw) {
    UUID id = Api.uuid(raw);
    if (id == null) throw notFound();
    return id;
  }
  static Api.Problem notFound() { return new Api.Problem(HttpStatus.NOT_FOUND, "NOT_FOUND", "PAYMENT_NOT_FOUND", List.of()); }
}
