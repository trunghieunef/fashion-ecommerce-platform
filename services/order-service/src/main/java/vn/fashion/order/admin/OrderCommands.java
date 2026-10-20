package vn.fashion.order.admin;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.fashion.order.orders.OrderService;
import vn.fashion.order.orders.OrderView;
import vn.fashion.order.saga.OrderTransitions;
import vn.fashion.order.web.Api;
import vn.fashion.platform.idempotency.IdempotencyStore;

/**
 * OPS confirm-cod and cancel (spec section 3): one transaction locks order_sagas then orders (the worker's order), checks
 * version, then state, then deadline (confirm) and a live worker lease; writes saga + order + audit_logs + the idempotent response together.
 */
@Component
public class OrderCommands {
  private record Locked(UUID id, String status, String method, long version, Instant expiresAt, String intent, String step,
                        boolean leased) { }
  private final JdbcClient jdbc;
  private final TransactionTemplate tx;
  private final IdempotencyStore keys;
  private final OrderService orders;
  private final OrderTransitions transitions;
  private final ObjectMapper json;
  private final Clock clock;

  public OrderCommands(JdbcClient jdbc, TransactionTemplate tx, IdempotencyStore keys, OrderService orders, OrderTransitions transitions,
                       ObjectMapper json, Clock clock) {
    this.jdbc = jdbc; this.tx = tx; this.keys = keys; this.orders = orders; this.transitions = transitions; this.json = json; this.clock = clock;
  }

  /** CONFIRMED stays CONFIRMED (PACKING only after commit); the saga moves to COMMIT_STOCK for the worker. */
  public JsonNode confirmCod(AdminAuth.AdminActor a, String orderNo, String key, long expectedVersion, String note) {
    return run(a, "confirm-cod", orderNo, key, expectedVersion, note, "ORDER_CONFIRM_COD", (o, request) -> {
      saga(o.id(), "step = 'COMMIT_STOCK', status = 'RUNNING'");
      jdbc.sql("update orders set version = version + 1, updated_at = now() where id = :id").param("id", o.id()).update();
    });
  }

  /** CONFIRMED -> CANCELLING by ADMIN; the ORD-02 compensation releases, closes and ends CANCELLED (OPS_CANCELLED). */
  public JsonNode cancel(AdminAuth.AdminActor a, String orderNo, String key, long expectedVersion, String reason) {
    return run(a, "cancel", orderNo, key, expectedVersion, reason, "ORDER_CANCEL", (o, request) -> {
      saga(o.id(), "intent = 'CANCEL', step = 'COMPENSATE', status = 'COMPENSATING'");
      transitions.move(o.id(), "CONFIRMED", "CANCELLING", reason, "OPS_CANCELLED", new OrderTransitions.Actor("ADMIN", a.userId(), request));
    });
  }

  private interface Work { void apply(Locked order, UUID requestId); }

  private JsonNode run(AdminAuth.AdminActor a, String operation, String orderNo, String key, long expectedVersion, String text,
                       String action, Work work) {
    String actorKey = "admin:" + a.userId();
    // order_no is part of the hash: the same key on another order is a reuse, never another order's stored response.
    String hash = Api.sha256(json.writeValueAsString(new Object[] {orderNo, expectedVersion, text}));
    return tx.execute(status -> {
      Object stored = Api.replay(keys.begin(actorKey, operation, key, hash), json);
      if (stored != null) return (JsonNode) stored;
      var o = lock(orderNo);
      // Version first: of a confirm and a cancel racing on one version, the loser gets VERSION_CONFLICT (spec section 3).
      if (o.version() != expectedVersion)
        throw new Api.Problem(HttpStatus.CONFLICT, "VERSION_CONFLICT", "VERSION_CONFLICT",
            List.of(new Api.FieldError("expected_version", "current=" + o.version())));
      // Only an order waiting for OPS; past WAIT_OPS (already confirmed) the cancel belongs to ORD-04.
      if (!o.status().equals("CONFIRMED") || !"FULFILL".equals(o.intent()) || !"WAIT_OPS".equals(o.step())) throw invalid(o.status());
      boolean confirm = operation.equals("confirm-cod");
      if (confirm && !o.method().equals("COD")) throw invalid(o.status());
      // A cancel is still allowed past the deadline the worker has not acted on yet (same outcome as EXPIRED).
      if (confirm && !clock.instant().isBefore(o.expiresAt()))
        throw new Api.Problem(HttpStatus.CONFLICT, "CONFLICT", "ORDER_EXPIRED", List.of());
      // A worker holds the saga (e.g. cancelling it at the deadline): let it finish, OPS reloads.
      if (o.leased()) throw invalid(o.status());
      UUID request = UUID.randomUUID();
      work.apply(o, request);
      var after = orders.view(o.id());
      jdbc.sql("""
          insert into audit_logs(id, actor_id, action, resource_type, resource_id, reason, before_data, after_data, request_id)
          values (:id, :actor, :action, 'order', :no, :reason, cast(:before as jsonb), cast(:after as jsonb), :request)""")
          .param("id", UUID.randomUUID()).param("actor", a.userId()).param("action", action).param("no", orderNo).param("reason", text)
          .param("before", json.writeValueAsString(Map.of("status", o.status(), "version", o.version())))
          .param("after", json.writeValueAsString(Map.of("status", after.status(), "version", after.version())))
          .param("request", request).update();
      // Without order.fulfill (cancel by order.cancel only) the response carries no contact, address or note (13).
      var view = a.permissions().contains("order.fulfill") ? after : new OrderView(after.orderId(), after.orderNo(), after.status(),
          after.processingStatus(), after.reasonCode(), after.statusUrl(), after.retryAfterSeconds(), after.version(), after.method(),
          after.items(), after.totals(), null, null, null, after.createdAt(), after.expiresAt(), after.allowedActions());
      String body = json.writeValueAsString(view);
      keys.finish(actorKey, operation, key, o.id(), HttpStatus.ACCEPTED.value(), body);
      return json.readTree(body);
    });
  }

  /** Locks order_sagas then orders, the worker's order (SagaRepository.write, then move), so the two never deadlock. */
  private Locked lock(String orderNo) {
    UUID id = jdbc.sql("select id from orders where order_no = :no").param("no", orderNo).query(UUID.class).optional()
        .orElseThrow(() -> new Api.Problem(HttpStatus.NOT_FOUND, "NOT_FOUND", "ORDER_NOT_FOUND", List.of()));
    var saga = jdbc.sql("select intent, step, coalesce(lease_until > now(), false) from order_sagas where order_id = :id for update")
        .param("id", id).query((rs, n) -> new Object[] {rs.getString(1), rs.getString(2), rs.getBoolean(3)}).optional()
        .orElse(new Object[] {null, null, false});
    var order = jdbc.sql("select status, payment_method, version, expires_at from orders where id = :id for update")
        .param("id", id).query((rs, n) -> new Object[] {rs.getString(1), rs.getString(2), rs.getLong(3), rs.getTimestamp(4).toInstant()})
        .single();
    return new Locked(id, (String) order[0], (String) order[1], (long) order[2], (Instant) order[3], (String) saga[0], (String) saga[1],
        (boolean) saga[2]);
  }

  /** Due now, attempts reset, version bumped and lease cleared so no earlier worker claim can still write (CAS by lease). */
  private void saga(UUID orderId, String set) {
    jdbc.sql("update order_sagas set " + set + """
        , next_retry_at = :now, retry_count = 0, last_error = null, version = version + 1, lease_token = null, lease_until = null,
          updated_at = now() where order_id = :id""")
        .param("now", Timestamp.from(clock.instant())).param("id", orderId).update();
  }

  private static Api.Problem invalid(String status) {
    return new Api.Problem(HttpStatus.CONFLICT, "CONFLICT", "INVALID_TRANSITION", List.of(new Api.FieldError("status", "current=" + status)));
  }
}
