package vn.fashion.shipping.shipments;

import io.micrometer.tracing.Tracer;
import java.util.List;
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
import tools.jackson.databind.ObjectMapper;
import vn.fashion.shipping.internal.InternalCallers;
import vn.fashion.shipping.web.Api;

/** Internal shipment API for order-service (03 section 4); addresses are never returned here. */
@RestController
@RequestMapping("/internal/api/v1/shipping/shipments")
public class ShipmentController {
  private final ShipmentService shipments;
  private final InternalCallers callers;
  private final ObjectMapper json;
  private final Tracer tracer;

  public ShipmentController(ShipmentService shipments, InternalCallers callers, ObjectMapper json, Tracer tracer) {
    this.shipments = shipments; this.callers = callers; this.json = json; this.tracer = tracer;
  }

  @PostMapping
  public ResponseEntity<?> create(@RequestHeader(name = "Authorization", required = false) String authorization,
                                  @RequestHeader(name = "Idempotency-Key", required = false) String key,
                                  @RequestBody(required = false) String body) {
    callers.require(authorization);
    Api.requireKey(key);
    var meta = Api.metadata(tracer);
    var request = ShipmentService.parse(Api.object(json, body));
    return Api.ok(HttpStatus.OK, shipments.create(key, request, meta.traceId()), meta);
  }

  @GetMapping("/by-order/{orderId}")
  public ResponseEntity<?> byOrder(@RequestHeader(name = "Authorization", required = false) String authorization,
                                   @PathVariable String orderId) {
    callers.require(authorization);
    return Api.ok(HttpStatus.OK, shipments.byOrder(id(orderId)).orElseThrow(ShipmentController::notFound), tracer);
  }

  static UUID id(String raw) {
    UUID id = Api.uuid(raw);
    if (id == null) throw notFound();
    return id;
  }
  static Api.Problem notFound() { return new Api.Problem(HttpStatus.NOT_FOUND, "NOT_FOUND", "SHIPMENT_NOT_FOUND", List.of()); }
}
