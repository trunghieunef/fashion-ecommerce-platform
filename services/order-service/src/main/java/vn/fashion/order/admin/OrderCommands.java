package vn.fashion.order.admin;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import vn.fashion.order.orders.OrderService;
import vn.fashion.order.saga.OrderTransitions;
import vn.fashion.order.web.Api;
import vn.fashion.platform.idempotency.IdempotencyStore;

/**
 * OPS confirm-cod and cancel (ORD-03 spec section 3) and the customer cancel (ORD-04 spec section 3) share one core: a
 * transaction locks order_sagas then orders (the worker's order), checks version, then state (admission), then a live worker
 * lease; writes saga + order + audit_logs (admin only) + the idempotent response together.
 */
@Component
public class OrderCommands {
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
    return execute(new Command("admin:" + a.userId(), "confirm-cod", orderNo, key, expectedVersion, note, o -> {
      admitWaitingForOps(o);
      if (!o.method().equals("COD")) throw invalid(o.status());
      // A cancel is still allowed past the deadline the worker has not acted on yet (same outcome as EXPIRED).
      if (!clock.instant().isBefore(o.expiresAt())) throw new Api.Problem(HttpStatus.CONFLICT, "CONFLICT", "ORDER_EXPIRED", List.of());
    }, (o, request) -> {
      saga(o.id(), "step = 'COMMIT_STOCK', status = 'RUNNING'");
      jdbc.sql("update orders set version = version + 1, updated_at = now() where id = :id").param("id", o.id()).update();
    }, new Audit(a.userId(), "ORDER_CONFIRM_COD"), id -> adminView(a, id)));
  }

  /** CONFIRMED or PACKING -> CANCELLING by ADMIN (ORD-04 spec section 4); the compensation then ends CANCELLED (OPS_CANCELLED). */
  public JsonNode cancel(AdminAuth.AdminActor a, String orderNo, String key, long expectedVersion, String reason) {
    return execute(new Command("admin:" + a.userId(), "cancel", orderNo, key, expectedVersion, reason, OrderCommands::admitOpsCancel,
        cancelWork(reason, "OPS_CANCELLED", "ADMIN", a.userId()), new Audit(a.userId(), "ORDER_CANCEL"), id -> adminView(a, id)));
  }

  /**
   * ORD-04 spec section 3: the owner (already authenticated by OrderService.owned) cancels while the order waits for OPS.
   * Idempotency actor = orders.actor_key; history actor CUSTOMER with the customer's text; no audit_logs; the customer view.
   */
  public JsonNode customerCancel(OrderService.Row owner, String key, long expectedVersion, String reason) {
    return execute(new Command(owner.actorKey(), "customer-cancel", owner.orderNo(), key, expectedVersion, reason,
        OrderCommands::admitWaitingForOps, cancelWork(reason, "CUSTOMER_CANCELLED", "CUSTOMER", owner.userId()), null,
        id -> json.valueToTree(orders.view(id))));
  }

  /** The order as locked by {@link #execute}. */
  public record Locked(UUID id, String status, String method, long version, Instant expiresAt, String intent, String step, boolean leased) { }
  public interface Work { void apply(Locked order, UUID requestId); }
  public record Audit(UUID actorId, String action) { }
  /**
   * admit throws a 409 when the locked order does not accept the command; audit null = none; view renders the response;
   * payload (null = none) is the rest of the body, part of the idempotency hash (return slip items).
   */
  public record Command(String idempotencyActor, String operation, String orderNo, String key, long expectedVersion, String text,
                        Consumer<Locked> admit, Work work, Audit audit, Function<UUID, JsonNode> view, Object payload) {
    public Command(String idempotencyActor, String operation, String orderNo, String key, long expectedVersion, String text,
                   Consumer<Locked> admit, Work work, Audit audit, Function<UUID, JsonNode> view) {
      this(idempotencyActor, operation, orderNo, key, expectedVersion, text, admit, work, audit, view, null);
    }
  }

  /** Only an order waiting for OPS (CONFIRMED, saga FULFILL/WAIT_OPS). */
  private static void admitWaitingForOps(Locked o) {
    if (!o.status().equals("CONFIRMED") || !"FULFILL".equals(o.intent()) || !"WAIT_OPS".equals(o.step())) throw invalid(o.status());
  }

  /** ORD-04 spec section 4: CONFIRMED at WAIT_OPS/COMMIT_STOCK or PACKING at CREATE_SHIPMENT/DONE, any saga status, forward intent. */
  private static void admitOpsCancel(Locked o) {
    boolean ok = "FULFILL".equals(o.intent()) && switch (o.status()) {
      case "CONFIRMED" -> "WAIT_OPS".equals(o.step()) || "COMMIT_STOCK".equals(o.step());
      case "PACKING" -> "CREATE_SHIPMENT".equals(o.step()) || "DONE".equals(o.step());
      default -> false;
    };
    if (!ok) throw invalid(o.status());
  }

  private Work cancelWork(String reason, String cancelReason, String actorType, UUID actorId) {
    return (o, request) -> {
      saga(o.id(), "intent = 'CANCEL', step = 'COMPENSATE', status = 'COMPENSATING'");
      transitions.move(o.id(), o.status(), "CANCELLING", reason, cancelReason, new OrderTransitions.Actor(actorType, actorId, request));
    };
  }

  /** Without order.fulfill (cancel by order.cancel only) the keys contact, address and note are omitted, like the GETs (13). */
  JsonNode adminView(AdminAuth.AdminActor a, UUID id) {
    ObjectNode view = json.valueToTree(orders.view(id));
    if (!a.permissions().contains("order.fulfill")) view.remove(List.of("contact", "shipping_address", "note"));
    return view;
  }

  /** Lock, version, admission, lease, work, optional audit and the idempotent 202 response, in one transaction. */
  public JsonNode execute(Command c) {
    // order_no is part of the hash: the same key on another order is a reuse, never another order's stored response.
    String hash = Api.sha256(json.writeValueAsString(c.payload() == null ? new Object[] {c.orderNo(), c.expectedVersion(), c.text()}
        : new Object[] {c.orderNo(), c.expectedVersion(), c.text(), c.payload()}));
    return tx.execute(status -> {
      Object stored = Api.replay(keys.begin(c.idempotencyActor(), c.operation(), c.key(), hash), json);
      if (stored != null) return (JsonNode) stored;
      var o = lock(c.orderNo());
      // Version first: of a confirm and a cancel racing on one version, the loser gets VERSION_CONFLICT (spec section 3).
      if (o.version() != c.expectedVersion())
        throw new Api.Problem(HttpStatus.CONFLICT, "VERSION_CONFLICT", "VERSION_CONFLICT",
            List.of(new Api.FieldError("expected_version", "current=" + o.version())));
      c.admit().accept(o);
      // A worker holds the saga (e.g. cancelling it at the deadline): let it finish, the caller reloads (PR #34 review M4).
      if (o.leased()) throw new Api.Problem(HttpStatus.CONFLICT, "CONFLICT", "SAGA_BUSY", List.of());
      UUID request = UUID.randomUUID();
      c.work().apply(o, request);
      JsonNode view = c.view().apply(o.id());
      if (c.audit() != null)
        jdbc.sql("""
            insert into audit_logs(id, actor_id, action, resource_type, resource_id, reason, before_data, after_data, request_id)
            values (:id, :actor, :action, 'order', :no, :reason, cast(:before as jsonb), cast(:after as jsonb), :request)""")
            .param("id", UUID.randomUUID()).param("actor", c.audit().actorId()).param("action", c.audit().action()).param("no", c.orderNo())
            .param("reason", c.text())
            .param("before", json.writeValueAsString(Map.of("status", o.status(), "version", o.version())))
            .param("after", json.writeValueAsString(Map.of("status", view.path("status").asString(), "version", view.path("version").asLong())))
            .param("request", request).update();
      String body = json.writeValueAsString(view);
      keys.finish(c.idempotencyActor(), c.operation(), c.key(), o.id(), HttpStatus.ACCEPTED.value(), body);
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

  static Api.Problem invalid(String status) {
    return new Api.Problem(HttpStatus.CONFLICT, "CONFLICT", "INVALID_TRANSITION", List.of(new Api.FieldError("status", "current=" + status)));
  }
}
