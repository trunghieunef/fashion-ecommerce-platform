package vn.fashion.payment.payments;

import io.micrometer.tracing.Tracer;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import vn.fashion.payment.web.AdminAuth;
import vn.fashion.payment.web.Api;

/** FINANCE payments (13): list/detail (payment.read), COD settlement (payment.settle); auth, then key, then body. */
@RestController
@RequestMapping("/admin/api/v1/payments")
public class PaymentAdminController {
  static final Set<String> STATUSES = Set.of("CREATED", "PROCESSING", "UNKNOWN", "SUCCESS", "FAILED", "PARTIALLY_REFUNDED", "REFUNDED",
      "COD_PENDING", "COD_COLLECTED");
  public record Summary(UUID paymentId, UUID orderId, String orderNo, String method, long amount, String status, Instant closedAt,
                        Instant paidAt, Instant createdAt) { }
  private final AdminAuth auth;
  private final JdbcClient jdbc;
  private final ObjectMapper json;
  private final Tracer tracer;
  private final SettlementService settlements;

  public PaymentAdminController(AdminAuth auth, JdbcClient jdbc, ObjectMapper json, Tracer tracer, SettlementService settlements) {
    this.auth = auth; this.jdbc = jdbc; this.json = json; this.tracer = tracer; this.settlements = settlements;
  }

  @GetMapping
  public ResponseEntity<?> list(@RequestHeader(name = "Authorization", required = false) String authorization,
                                @RequestParam(required = false) String status, @RequestParam(required = false) String cursor,
                                @RequestParam(required = false) String limit) {
    auth.require(authorization, "payment.read");
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
        select id as payment_id, order_id, order_no, method, amount, status, closed_at, paid_at, created_at
        from payments where (cast(:status as text) is null or status = :status)
          and (cast(:at as timestamptz) is null or (created_at, id) < (cast(:at as timestamptz), cast(:id as uuid)))
        order by created_at desc, id desc limit :n""")
        .param("status", status).param("at", afterAt == null ? null : Timestamp.from(afterAt)).param("id", afterId).param("n", size + 1)
        .query(Summary.class).list();
    String next = null;
    if (rows.size() > size) {
      var last = rows.get(size - 1);
      next = Base64.getUrlEncoder().withoutPadding().encodeToString((last.createdAt() + "|" + last.paymentId()).getBytes(StandardCharsets.UTF_8));
      rows = rows.subList(0, size);
    }
    var page = new LinkedHashMap<String, Object>();
    page.put("items", rows);
    page.put("next_cursor", next);
    return Api.ok(HttpStatus.OK, page, tracer);
  }

  /** Payment with its evidence rows, settlements and reconciliation items; no customer PII is stored here (user_id only). */
  @GetMapping("/{id}")
  public ResponseEntity<?> detail(@RequestHeader(name = "Authorization", required = false) String authorization, @PathVariable String id) {
    auth.require(authorization, "payment.read");
    UUID paymentId = PaymentController.id(id);
    var detail = rows("""
        select id as payment_id, order_id, order_no, user_id, method, amount, currency, status, provider_request_id, closed_at,
          close_reason, paid_at, refunded_amount, refund_reserved_amount, version, created_at, updated_at from payments where id = :id""", paymentId)
        .stream().findFirst().orElseThrow(PaymentController::notFound);
    detail.put("transactions", rows("""
        select source, provider_event_key, status, amount, occurred_at, sanitized_payload::text as evidence, created_at
        from payment_transactions where payment_id = :id order by created_at, id""", paymentId));
    detail.put("settlements", rows("""
        select settlement_ref, gross_amount, fee_amount, net_amount, evidence_ref, confirmed_by, created_at
        from cod_settlements where payment_id = :id order by created_at, id""", paymentId));
    detail.put("reconciliation_items", rows("""
        select id, source_ref, kind, expected_amount, observed_amount, status, evidence::text as evidence, resolved_by, resolved_at,
          created_at from reconciliation_items where payment_id = :id order by created_at, id""", paymentId));
    return Api.ok(HttpStatus.OK, detail, tracer);
  }

  @PostMapping("/{id}/cod-settlements")
  public ResponseEntity<?> settle(@RequestHeader(name = "Authorization", required = false) String authorization,
                                  @RequestHeader(name = "Idempotency-Key", required = false) String key,
                                  @PathVariable String id, @RequestBody(required = false) String body) {
    UUID actor = auth.require(authorization, "payment.settle");
    Api.requireKey(key);
    UUID paymentId = PaymentController.id(id);
    var settlement = SettlementService.parse(Api.object(json, body));
    var meta = Api.metadata(tracer);
    return Api.ok(HttpStatus.OK, settlements.settle(paymentId, actor, key, settlement, meta.traceId()), meta);
  }

  /** Rows as ordered maps: timestamps as Instant, *evidence jsonb text as JSON. */
  private List<Map<String, Object>> rows(String sql, UUID id) {
    return jdbc.sql(sql).param("id", id).query().listOfRows().stream().map(row -> {
      var out = new LinkedHashMap<String, Object>();
      row.forEach((k, v) -> out.put(k, k.equals("evidence") && v != null ? json.readTree((String) v)
          : v instanceof Timestamp t ? t.toInstant() : v));
      return (Map<String, Object>) out;
    }).toList();
  }

  private static Api.Problem invalid(String field) {
    return new Api.Problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "INVALID_PARAMETER", List.of(new Api.FieldError(field, "has an invalid value")));
  }
}
