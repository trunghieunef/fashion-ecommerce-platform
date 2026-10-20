package vn.fashion.shipping.shipments;

import io.micrometer.tracing.Tracer;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;
import vn.fashion.shipping.web.AdminAuth;
import vn.fashion.shipping.web.Api;

/** OPS shipments: list newest first with keyset cursor, detail with history (shipping.read); SELF events (SHP-02). */
@RestController
@RequestMapping("/admin/api/v1/shipping/shipments")
public class ShipmentAdminController {
  static final Set<String> STATUSES = Set.of("PENDING", "UNKNOWN", "CREATED", "PICKING", "DELIVERING", "DELIVERED", "FAILED",
      "RETURNING", "RETURNED", "CANCEL_PENDING", "CANCELLED", "MANUAL");
  public record Summary(UUID shipmentId, UUID orderId, String orderNo, String status, String carrier, String trackingCode, long fee,
                        long codAmount, Instant handedOverAt, Instant createdAt) { }
  private final AdminAuth auth;
  private final JdbcClient jdbc;
  private final ObjectMapper json;
  private final Tracer tracer;
  private final ShipmentService shipments;
  private final SelfEventService selfEvents;

  public ShipmentAdminController(AdminAuth auth, JdbcClient jdbc, ObjectMapper json, Tracer tracer, ShipmentService shipments,
                                 SelfEventService selfEvents) {
    this.auth = auth; this.jdbc = jdbc; this.json = json; this.tracer = tracer; this.shipments = shipments; this.selfEvents = selfEvents;
  }

  @GetMapping
  public ResponseEntity<?> list(@RequestHeader(name = "Authorization", required = false) String authorization,
                                @RequestParam(required = false) String status, @RequestParam(required = false) String cursor,
                                @RequestParam(required = false) String limit) {
    auth.require(authorization, "shipping.read");
    if (status != null && !STATUSES.contains(status)) throw invalid("status");
    int size = 20;
    if (limit != null) {
      try { size = Integer.parseInt(limit); } catch (NumberFormatException e) { size = 0; }
      if (size < 1 || size > 100) throw invalid("limit");
    }
    Instant afterAt = null;
    UUID afterId = null;
    if (cursor != null) {
      try {
        String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|");
        afterAt = Instant.parse(parts[0]);
        afterId = UUID.fromString(parts[1]);
      } catch (RuntimeException e) { throw invalid("cursor"); }
    }
    var rows = jdbc.sql("""
        select id as shipment_id, order_id, order_no, status, carrier, tracking_code, fee, cod_amount, handed_over_at, created_at
        from shipments where (cast(:status as text) is null or status = :status)
          and (cast(:at as timestamptz) is null or (created_at, id) < (cast(:at as timestamptz), cast(:id as uuid)))
        order by created_at desc, id desc limit :n""")
        .param("status", status).param("at", afterAt == null ? null : Timestamp.from(afterAt)).param("id", afterId).param("n", size + 1)
        .query(Summary.class).list();
    String next = null;
    if (rows.size() > size) {
      var last = rows.get(size - 1);
      next = Base64.getUrlEncoder().withoutPadding().encodeToString((last.createdAt() + "|" + last.shipmentId()).getBytes(StandardCharsets.UTF_8));
      rows = rows.subList(0, size);
    }
    var page = new LinkedHashMap<String, Object>();
    page.put("items", rows);
    page.put("next_cursor", next);
    return Api.ok(HttpStatus.OK, page, tracer);
  }

  @GetMapping("/{id}")
  public ResponseEntity<?> detail(@RequestHeader(name = "Authorization", required = false) String authorization, @PathVariable String id) {
    auth.require(authorization, "shipping.read");
    return Api.ok(HttpStatus.OK, shipments.adminDetail(ShipmentController.id(id)), tracer);
  }

  /** SHP-02: SELF delivery/COD event with evidence (permission shipping.self_event); auth, then key, then body. */
  @PostMapping("/{id}/self-events")
  public ResponseEntity<?> selfEvent(@RequestHeader(name = "Authorization", required = false) String authorization,
                                     @RequestHeader(name = "Idempotency-Key", required = false) String key,
                                     @PathVariable String id, @RequestBody(required = false) String body) {
    UUID actor = auth.require(authorization, "shipping.self_event");
    Api.requireKey(key);
    UUID shipmentId = ShipmentController.id(id);
    var event = SelfEventService.parse(Api.object(json, body));
    var meta = Api.metadata(tracer);
    return Api.ok(HttpStatus.OK, selfEvents.apply(shipmentId, actor, key, event, meta.traceId()), meta);
  }

  private static Api.Problem invalid(String field) {
    return new Api.Problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "INVALID_PARAMETER", List.of(new Api.FieldError(field, "has an invalid value")));
  }
}
