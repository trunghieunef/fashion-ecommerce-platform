package vn.fashion.order.admin;

import io.micrometer.tracing.Tracer;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.fashion.order.web.Api;

/** OPS/FINANCE orders (13, spec section 5): order.read lists and reads; contact data and phone search need order.fulfill. */
@RestController
@RequestMapping("/admin/api/v1/orders")
public class OrderAdminController {
  private final AdminAuth auth;
  private final OrderAdminQueries queries;
  private final Tracer tracer;

  public OrderAdminController(AdminAuth auth, OrderAdminQueries queries, Tracer tracer) { this.auth = auth; this.queries = queries; this.tracer = tracer; }

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
}
