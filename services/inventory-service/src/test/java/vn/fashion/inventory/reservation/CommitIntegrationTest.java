package vn.fashion.inventory.reservation;

import static org.assertj.core.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CommitIntegrationTest extends ReservationTestSupport {
  ReservationSnapshot reservedOrder(String sku,int onHand,int q) {
    seedStock(sku,onHand,0);var s=snapshot(UUID.randomUUID(),in(15),sku,q);data(reserve(s),201);return s;
  }
  @Test void commitDeductsOnceAndRepeatIsIdempotent() {
    var s=reservedOrder("SKU-A",5,2);
    var r=commit(s.orderId());var d=data(r,200);noStore(r);
    assertThat(d.path("status").asString()).isEqualTo("COMMITTED");assertThat(d.path("version").asLong()).isEqualTo(2);
    assertThat(d.path("committed_at").isNull()).isFalse();
    assertThat(onHand("SKU-A")).isEqualTo(3);assertThat(reserved("SKU-A")).isZero();
    assertThat(data(commit(s.orderId()),200).path("version").asLong()).isEqualTo(2);
    assertThat(onHand("SKU-A")).isEqualTo(3);assertThat(ledger("COMMIT")).isEqualTo(1);
    assertThat(outbox("INVENTORY_DEDUCTED")).isEqualTo(1);
    assertThat(jdbc.queryForMap("select on_hand_delta,reserved_delta,order_id from stock_transactions where type='COMMIT'"))
        .containsEntry("on_hand_delta",-2).containsEntry("reserved_delta",-2).containsEntry("order_id",s.orderId());
  }
  @Test void t09QueryAfterCommitShowsCommitted() {
    var s=reservedOrder("SKU-A",5,1);data(commit(s.orderId()),200);
    assertThat(data(get(s.orderId()),200).path("status").asString()).isEqualTo("COMMITTED");
  }
  @Test void commitAfterDeadlineExpiresAndPersists() {
    var s=reservedOrder("SKU-A",5,2);
    jdbc.update("update stock_reservations set expires_at=now()-interval '1 second'");
    assertThat(problem(commit(s.orderId()),409)).isEqualTo("RESERVATION_EXPIRED");
    assertThat(jdbc.queryForObject("select status from stock_reservations",String.class)).isEqualTo("EXPIRED");
    assertThat(jdbc.queryForObject("select release_reason from stock_reservations",String.class)).isEqualTo("expired");
    assertThat(onHand("SKU-A")).isEqualTo(5);assertThat(reserved("SKU-A")).isZero();
    assertThat(ledger("RELEASE")).isEqualTo(1);assertThat(ledger("COMMIT")).isZero();
    var expired=jdbc.queryForMap("select version,released_at,release_reason,status from stock_reservations");
    assertThat(expired).containsEntry("version",2L);
    assertThat(expired.get("released_at")).isNotNull();
    assertThat(outbox("INVENTORY_RELEASED")).isEqualTo(1);
    assertThat(problem(commit(s.orderId()),409)).isEqualTo("RESERVATION_EXPIRED");assertThat(ledger("RELEASE")).isEqualTo(1);
    assertThat(jdbc.queryForMap("select version,released_at,release_reason,status from stock_reservations")).isEqualTo(expired);
    assertThat(onHand("SKU-A")).isEqualTo(5);assertThat(reserved("SKU-A")).isZero();
    assertThat(ledger("COMMIT")).isZero();assertThat(outbox("INVENTORY_RELEASED")).isEqualTo(1);
  }
  @Test void unknownOrderIs404AndBadBodyIs400() {
    assertThat(problem(commit(UUID.randomUUID()),404)).isEqualTo("RESERVATION_NOT_FOUND");
    assertThat(problem(post("commit",java.util.Map.of("order_id","x")),400)).isEqualTo("INVALID_INPUT");
    assertThat(problem(send("POST",BASE+"/commit",serviceToken("cart-service"),"k","{}"),403)).isEqualTo("CALLER_NOT_ALLOWED");
  }
}
