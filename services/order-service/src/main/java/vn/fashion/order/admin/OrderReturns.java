package vn.fashion.order.admin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import vn.fashion.order.web.Api;
import vn.fashion.platform.work.BackgroundTaskRepository;

/**
 * ORD-04 spec sections 5/6: an OPS return slip (counted goods) on a RETURNING, RETURNED or DELIVERED order. Under the
 * OrderCommands lock (order_sagas, then orders) the received total over every slip stays within the ordered quantity; the
 * slip, its items, the ORDER_RETURN task (restock, saga/OrderReturnWorker) and audit_logs are written in one transaction.
 */
@Component
public class OrderReturns {
  public static final String TASK = "ORDER_RETURN";
  private static final Set<String> OPEN = Set.of("RETURNING", "RETURNED", "DELIVERED");
  public record Line(UUID orderItemId, int receivedQuantity, int restockQuantity, String condition) { }
  private final JdbcClient jdbc;
  private final OrderCommands commands;
  private final OrderAdminQueries queries;
  private final BackgroundTaskRepository tasks;
  private final ObjectMapper json;

  public OrderReturns(JdbcClient jdbc, OrderCommands commands, OrderAdminQueries queries, BackgroundTaskRepository tasks, ObjectMapper json) {
    this.jdbc = jdbc; this.commands = commands; this.queries = queries; this.tasks = tasks; this.json = json;
  }

  /** Body items: 1..100, order_item_id unique, received >= 1, 0 <= restock <= received, condition <= 200 characters. */
  public static List<Line> lines(JsonNode body) {
    var items = body.path("items");
    if (!items.isArray() || items.isEmpty() || items.size() > 100) throw Api.fields("items", "must hold 1..100 items");
    var out = new ArrayList<Line>();
    var seen = new HashSet<UUID>();
    for (int i = 0; i < items.size(); i++) {
      var n = items.get(i);
      String at = "items[" + i + "]";
      if (!n.isObject()) throw Api.fields(at, "must be an object");
      UUID id = n.path("order_item_id").isString() ? Api.uuid(n.path("order_item_id").asString()) : null;
      if (id == null) throw Api.fields(at + ".order_item_id", "must be a UUID");
      if (!seen.add(id)) throw Api.fields(at + ".order_item_id", "is duplicated");
      int received = quantity(n, at, "received_quantity", 1);
      int restock = quantity(n, at, "restock_quantity", 0);
      if (restock > received) throw Api.fields(at + ".restock_quantity", "must not exceed received_quantity");
      var c = n.path("condition");
      if (!c.isMissingNode() && !c.isNull() && !c.isString()) throw Api.fields(at + ".condition", "must be a string");
      String condition = c.isString() && !c.asString().isBlank() ? c.asString().strip() : null;
      if (condition != null && condition.length() > 200) throw Api.fields(at + ".condition", "must be at most 200 characters");
      out.add(new Line(id, received, restock, condition));
    }
    return out;
  }
  private static int quantity(JsonNode n, String at, String field, int min) {
    var v = n.path(field);
    if (!v.isIntegralNumber() || !v.canConvertToInt() || v.asInt() < min) throw Api.fields(at + "." + field, "must be an integer >= " + min);
    return v.asInt();
  }

  /** 202: the admin order view plus the slip (REQUESTED); operation_key = the Idempotency-Key. */
  public JsonNode create(AdminAuth.AdminActor a, String orderNo, String key, long expectedVersion, String reason, String evidenceRef,
                         List<Line> lines) {
    UUID returnId = UUID.randomUUID();
    boolean pii = a.permissions().contains("order.fulfill");
    return commands.execute(new OrderCommands.Command("admin:" + a.userId(), "return", orderNo, key, expectedVersion, reason,
        o -> { if (!OPEN.contains(o.status())) throw OrderCommands.invalid(o.status()); },
        (o, request) -> record(o.id(), returnId, key, reason, evidenceRef, a.userId(), request, lines),
        new OrderCommands.Audit(a.userId(), "ORDER_RETURN"),
        id -> {
          ObjectNode view = (ObjectNode) commands.adminView(a, id);
          view.set("return", json.valueToTree(queries.returns(id, pii).stream()
              .filter(r -> returnId.equals(r.get("return_id"))).findFirst().orElseThrow()));
          return view;
        },
        payload(evidenceRef, lines)));
  }

  /**
   * PR #35 review I2: void a MANUAL slip (inventory refused it and wrote nothing) so it leaves the received sum and OPS can
   * count again. 202 + the order view; another status → 409 RETURN_NOT_MANUAL; unknown slip of this order → 404.
   */
  public JsonNode voidSlip(AdminAuth.AdminActor a, String orderNo, UUID returnId, String key, long expectedVersion, String reason) {
    return commands.execute(new OrderCommands.Command("admin:" + a.userId(), "return-void", orderNo, key, expectedVersion, reason,
        o -> { if (!OPEN.contains(o.status())) throw OrderCommands.invalid(o.status()); },
        (o, request) -> {
          var status = jdbc.sql("select status from order_returns where id = :r and order_id = :o for update")
              .param("r", returnId).param("o", o.id()).query(String.class).optional()
              .orElseThrow(() -> new Api.Problem(HttpStatus.NOT_FOUND, "NOT_FOUND", "RETURN_NOT_FOUND", List.of()));
          if (!status.equals("MANUAL"))
            throw new Api.Problem(HttpStatus.CONFLICT, "CONFLICT", "RETURN_NOT_MANUAL",
                List.of(new Api.FieldError("status", "current=" + status)));
          jdbc.sql("update order_returns set status = 'VOIDED', updated_at = now() where id = :r").param("r", returnId).update();
          jdbc.sql("update orders set version = version + 1, updated_at = now() where id = :id").param("id", o.id()).update();
        },
        new OrderCommands.Audit(a.userId(), "ORDER_RETURN_VOID"),
        id -> commands.adminView(a, id),
        new Object[] {returnId}));
  }

  /** Idempotency hash input besides order_no/version/reason: fixed order (array, record components), never a Map.of. */
  public static Object[] payload(String evidenceRef, List<Line> lines) { return new Object[] {evidenceRef, lines}; }

  private void record(UUID orderId, UUID returnId, String key, String reason, String evidenceRef, UUID actor, UUID request, List<Line> lines) {
    var ordered = new HashMap<UUID, Integer>();
    jdbc.sql("select id, quantity from order_items where order_id = :id").param("id", orderId)
        .query((rs, n) -> ordered.put(rs.getObject(1, UUID.class), rs.getInt(2))).list();
    // Every slip of the order counts, read under the order lock so two slips never both fit (spec section 10 T17).
    var received = new HashMap<UUID, Integer>();
    jdbc.sql("""
        select ri.order_item_id, sum(ri.received_quantity) from order_return_items ri join order_returns r on r.id = ri.return_id
        where r.order_id = :id and r.status <> 'VOIDED' group by ri.order_item_id""").param("id", orderId)
        .query((rs, n) -> received.put(rs.getObject(1, UUID.class), rs.getInt(2))).list();
    var over = new ArrayList<Api.FieldError>();
    for (int i = 0; i < lines.size(); i++) {
      var line = lines.get(i);
      Integer quantity = ordered.get(line.orderItemId());
      if (quantity == null) throw Api.fields("items[" + i + "].order_item_id", "is not an item of the order");
      int done = received.getOrDefault(line.orderItemId(), 0);
      if (done + line.receivedQuantity() > quantity)
        over.add(new Api.FieldError("items[" + line.orderItemId() + "]",
            "ordered=" + quantity + " received=" + done + " requested=" + line.receivedQuantity()));
    }
    if (!over.isEmpty()) throw new Api.Problem(HttpStatus.CONFLICT, "CONFLICT", "RETURN_EXCEEDS_ORDERED", over);
    // Idempotency is per actor: another OPS reusing this key on the order hits UNIQUE(order_id, operation_key) — 409, not 500
    // (PR #35 review M3).
    int inserted = jdbc.sql("""
        insert into order_returns(id, order_id, operation_key, reason, evidence_ref, actor_id, request_id)
        values (:id, :order, :key, :reason, :evidence, :actor, :request) on conflict (order_id, operation_key) do nothing""")
        .param("id", returnId).param("order", orderId).param("key", key).param("reason", reason).param("evidence", evidenceRef)
        .param("actor", actor).param("request", request).update();
    if (inserted == 0) throw new Api.Problem(HttpStatus.CONFLICT, "CONFLICT", "IDEMPOTENCY_KEY_REUSED", List.of());
    for (var line : lines)
      jdbc.sql("""
          insert into order_return_items(return_id, order_item_id, received_quantity, restock_quantity, condition)
          values (:id, :item, :received, :restock, :condition)""")
          .param("id", returnId).param("item", line.orderItemId()).param("received", line.receivedQuantity())
          .param("restock", line.restockQuantity()).param("condition", line.condition()).update();
    tasks.enqueue(TASK, returnId.toString(), "{\"return_id\":\"" + returnId + "\"}");
    jdbc.sql("update orders set version = version + 1, updated_at = now() where id = :id").param("id", orderId).update();
  }
}
