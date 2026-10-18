package vn.fashion.inventory.reservation;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ReservationRepository {
  private static final String COLUMNS="r.*, r.expires_at <= now() as due";
  private final JdbcClient jdbc;
  public ReservationRepository(JdbcClient jdbc) { this.jdbc=jdbc; }
  public Optional<ReservationRow> lock(UUID orderId) {
    return jdbc.sql("select "+COLUMNS+" from stock_reservations r where order_id=:o for update").param("o",orderId)
        .query(ReservationRow.class).optional();
  }
  public Optional<ReservationRow> find(UUID orderId) {
    return jdbc.sql("select "+COLUMNS+" from stock_reservations r where order_id=:o").param("o",orderId)
        .query(ReservationRow.class).optional();
  }
  /** Inserts ACTIVE or a RELEASED tombstone; empty when the order already has a row (waits for a concurrent insert). */
  public Optional<ReservationRow> insertIfAbsent(UUID id,ReservationSnapshot s,String hash,String status,String reason) {
    return jdbc.sql("""
        insert into stock_reservations(id,order_id,order_no,request_hash,status,expires_at,released_at,release_reason,version)
        values (:id,:o,:no,:hash,:status,:expires,case when :status='ACTIVE' then null else now() end,:reason,1)
        on conflict (order_id) do nothing returning *, expires_at <= now() as due
        """).param("id",id).param("o",s.orderId()).param("no",s.orderNo()).param("hash",hash).param("status",status)
        .param("expires",OffsetDateTime.ofInstant(s.expiresAt(),ZoneOffset.UTC)).param("reason",reason)
        .query(ReservationRow.class).optional();
  }
  public void insertItems(UUID id,List<ReservationSnapshot.Item> items) {
    jdbc.sql("insert into stock_reservation_items(reservation_id,sku,quantity) select :id,t.sku,t.q from unnest(cast(:skus as text[]),cast(:qs as int[])) as t(sku,q)")
        .param("id",id).param("skus",items.stream().map(ReservationSnapshot.Item::sku).toArray(String[]::new))
        .param("qs",items.stream().map(ReservationSnapshot.Item::quantity).toArray(Integer[]::new)).update();
  }
  public List<ReservationSnapshot.Item> items(UUID id) {
    return jdbc.sql("select sku,quantity from stock_reservation_items where reservation_id=:id order by sku").param("id",id)
        .query((rs,n)->new ReservationSnapshot.Item(rs.getString("sku"),rs.getInt("quantity"))).list();
  }
  public ReservationRow transition(UUID id,String status,String reason) {
    return jdbc.sql("""
        update stock_reservations r set status=:status,version=version+1,updated_at=now(),
          committed_at=case when :status='COMMITTED' then now() else committed_at end,
          released_at=case when :status in ('RELEASED','EXPIRED') then now() else released_at end,
          release_reason=coalesce(:reason,release_reason)
        where id=:id returning r.*, r.expires_at <= now() as due
        """).param("id",id).param("status",status).param("reason",reason).query(ReservationRow.class).single();
  }
  public List<UUID> dueIds(int limit) {
    return jdbc.sql("select id from stock_reservations where status='ACTIVE' and expires_at<=now() order by expires_at limit :n")
        .param("n",limit).query(UUID.class).list();
  }
  /** Skips a row another transaction holds (a commit in flight), so the sweep never waits on checkout. */
  public Optional<ReservationRow> lockDue(UUID id) {
    return jdbc.sql("select "+COLUMNS+" from stock_reservations r where id=:id and status='ACTIVE' and expires_at<=now() for update skip locked")
        .param("id",id).query(ReservationRow.class).optional();
  }
  /** INV-03: total returned per SKU of a reservation. */
  public Map<String,Integer> returnedTotals(UUID reservationId) {
    var totals=new HashMap<String,Integer>();
    jdbc.sql("select sku,sum(quantity)::int as total from stock_returns where reservation_id=:r group by sku").param("r",reservationId)
        .query(rs->{ totals.put(rs.getString("sku"),rs.getInt("total")); });
    return totals;
  }
  public long overdueCount() {
    return jdbc.sql("select count(*) from stock_reservations where status='ACTIVE' and expires_at<=now()").query(Long.class).single();
  }
}
