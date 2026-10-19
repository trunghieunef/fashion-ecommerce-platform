package vn.fashion.shipping.fee;

import io.micrometer.tracing.Tracer;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;
import vn.fashion.shipping.internal.InternalCallers;
import vn.fashion.shipping.web.Api;

/** Public estimate (reference only, nothing stored) and the order-service quote it snapshots (SHP-01 spec section 5). */
@RestController
public class FeeController {
  private final FeeService fees;
  private final InternalCallers callers;
  private final ObjectMapper json;
  private final Tracer tracer;

  public FeeController(FeeService fees, InternalCallers callers, ObjectMapper json, Tracer tracer) {
    this.fees = fees; this.callers = callers; this.json = json; this.tracer = tracer;
  }

  @PostMapping("/api/v1/shipping/fee/estimate")
  public ResponseEntity<?> estimate(@RequestBody(required = false) String body) {
    return Api.ok(HttpStatus.OK, fees.estimate(FeeService.parse(Api.object(json, body))), tracer);
  }

  @PostMapping("/internal/api/v1/shipping/quotes")
  public ResponseEntity<?> quote(@RequestHeader(name = "Authorization", required = false) String authorization,
                                 @RequestHeader(name = "Idempotency-Key", required = false) String key,
                                 @RequestBody(required = false) String body) {
    callers.require(authorization);
    Api.requireKey(key);
    return Api.ok(HttpStatus.OK, fees.quote(key, FeeService.parse(Api.object(json, body))), tracer);
  }
}
