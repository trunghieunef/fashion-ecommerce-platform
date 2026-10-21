package vn.fashion.order.admin;

import io.micrometer.tracing.Tracer;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;
import vn.fashion.order.web.Api;

/** OPS/FINANCE orders (13, spec section 5): order.read lists and reads; contact data and phone search need order.fulfill; order.fulfill confirms COD, order.cancel cancels. */
@RestController
@RequestMapping("/admin/api/v1/orders")
public class OrderAdminController {
  private final AdminAuth auth;
  private final OrderAdminQueries queries;
  private final OrderCommands commands;
  private final ObjectMapper json;
  private final Tracer tracer;

  public OrderAdminController(AdminAuth auth, OrderAdminQueries queries, OrderCommands commands, ObjectMapper json, Tracer tracer) {
    this.auth = auth; this.queries = queries; this.commands = commands; this.json = json; this.tracer = tracer;
  }

  @GetMapping
  public ResponseEntity<?> list(@RequestHeader(name = "Authorization", required = false) String authorization,
                                @RequestParam(required = false) String status, @RequestParam(required = false) String q,
                                @RequestParam(required = false) String cursor, @RequestParam(required = false) String limit) {
    var actor = auth.require(authorization, "order.read");
    boolean pii = actor.permissions().contains("order.fulfill");
    if (OrderAdminQueries.isPhoneQuery(q) && !pii) throw AdminAuth.forbidden();
    int size = 20;
    if (limit != null) {
      try { size = Integer.parseInt(limit); } catch (NumberFormatException e) { size = 0; }
      if (size < 1 || size > 100) throw new Api.Problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "INVALID_PARAMETER",
          List.of(new Api.FieldError("limit", "must be 1..100")));
    }
    return Api.ok(HttpStatus.OK, queries.page(status, q, cursor, size, pii), tracer);
  }

  @GetMapping("/{orderNo}")
  public ResponseEntity<?> detail(@RequestHeader(name = "Authorization", required = false) String authorization, @PathVariable String orderNo) {
    var actor = auth.require(authorization, "order.read");
    return Api.ok(HttpStatus.OK, queries.detail(orderNo, actor.permissions().contains("order.fulfill")), tracer);
  }

  /** Spec section 3: auth, then Idempotency-Key, then body {expected_version, note? (<= 500)}; 202 + the order. */
  @PostMapping("/{orderNo}/confirm-cod")
  public ResponseEntity<?> confirmCod(@RequestHeader(name = "Authorization", required = false) String authorization,
                                      @RequestHeader(name = "Idempotency-Key", required = false) String key,
                                      @PathVariable String orderNo, @RequestBody(required = false) String body) {
    var actor = auth.require(authorization, "order.fulfill");
    Api.requireKey(key);
    var node = Api.object(json, body);
    return Api.ok(HttpStatus.ACCEPTED, commands.confirmCod(actor, orderNo, key, Api.expectedVersion(node), Api.text(node, "note", false)), tracer);
  }

  /** Spec section 3: body {expected_version, reason (1..500)}; 202 + the order (CANCELLING). */
  @PostMapping("/{orderNo}/cancel")
  public ResponseEntity<?> cancel(@RequestHeader(name = "Authorization", required = false) String authorization,
                                  @RequestHeader(name = "Idempotency-Key", required = false) String key,
                                  @PathVariable String orderNo, @RequestBody(required = false) String body) {
    var actor = auth.require(authorization, "order.cancel");
    Api.requireKey(key);
    var node = Api.object(json, body);
    return Api.ok(HttpStatus.ACCEPTED, commands.cancel(actor, orderNo, key, Api.expectedVersion(node), Api.text(node, "reason", true)), tracer);
  }

}
