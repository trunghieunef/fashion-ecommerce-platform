package vn.fashion.inventory.reservation;

import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.fashion.inventory.admin.AdminCommands;
import vn.fashion.inventory.internal.InternalCallers;
import vn.fashion.inventory.web.Api;

/** Internal reservation API (03 section 4): order-service only; auth before headers and body. */
@RestController
@RequestMapping("/internal/api/v1/inventory")
public class ReservationController {
  private static final Set<String> CALLERS=Set.of("order-service");
  private final InternalCallers callers;
  private final ReservationService service;
  private final Tracer tracer;
  private final ObjectMapper json;
  public ReservationController(InternalCallers callers,ReservationService service,Tracer tracer,ObjectMapper json) {
    this.callers=callers;this.service=service;this.tracer=tracer;this.json=json;
  }
  @PostMapping("/reserve")
  public ResponseEntity<?> reserve(@RequestHeader(name="Authorization",required=false) String auth,
      @RequestHeader(name="Idempotency-Key",required=false) String key,HttpServletRequest request) {
    var meta=authorize(auth,key);
    return respond(service.reserve(ReservationRequests.reserve(body(request)),meta.traceId()),meta);
  }
  @GetMapping("/reservations/{orderId}")
  public ResponseEntity<?> get(@RequestHeader(name="Authorization",required=false) String auth,@PathVariable String orderId) {
    callers.require(auth,CALLERS);
    var meta=Api.metadata(tracer);
    return Api.ok(HttpStatus.OK,service.find(ReservationRequests.orderId(orderId,"order_id")),meta);
  }
  Api.Metadata authorize(String auth,String key) {
    callers.require(auth,CALLERS);AdminCommands.requireKey(key);return Api.metadata(tracer);
  }
  JsonNode body(HttpServletRequest request) {
    try {
      var node=json.readTree(request.getInputStream());
      if(node==null || node.isMissingNode()) throw malformed();
      return node;
    } catch(IOException | JacksonException e) { throw malformed(); }
  }
  ResponseEntity<?> respond(ReservationService.Outcome outcome,Api.Metadata meta) {
    if(outcome.error()!=null) throw ReservationService.conflict(outcome.error());
    return Api.ok(HttpStatus.valueOf(outcome.status()),outcome.view(),meta);
  }
  static Api.Problem malformed() { return new Api.Problem(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","MALFORMED_JSON",List.of()); }
}
