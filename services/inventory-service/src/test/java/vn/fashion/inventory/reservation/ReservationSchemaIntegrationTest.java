package vn.fashion.inventory.reservation;

import static org.assertj.core.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import vn.fashion.inventory.InventoryTestSupport;

class ReservationSchemaIntegrationTest extends InventoryTestSupport {
  UUID insert(String status, String committedAt, String releasedAt) {
    UUID id = UUID.randomUUID();
    jdbc.update("insert into stock_reservations(id,order_id,order_no,request_hash,status,expires_at,committed_at,released_at,version)"
        + " values (?,?,?,?,?,now()+interval '10 minutes'," + committedAt + "," + releasedAt + ",1)",
        id, UUID.randomUUID(), "ORD-1", "a".repeat(64), status);
    return id;
  }

  @Test void orderIdIsUnique() {
    UUID order = UUID.randomUUID();
    String sql = "insert into stock_reservations(id,order_id,order_no,request_hash,status,expires_at,version) values (?,?,'ORD-1',?,'ACTIVE',now(),1)";
    jdbc.update(sql, UUID.randomUUID(), order, "a".repeat(64));
    assertThatThrownBy(() -> jdbc.update(sql, UUID.randomUUID(), order, "b".repeat(64)))
        .isInstanceOf(DataAccessException.class);
  }

  @Test void statusAndTimestampsAreConsistent() {
    assertThatThrownBy(() -> insert("PENDING", "null", "null")).isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> insert("COMMITTED", "null", "null")).isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> insert("RELEASED", "null", "null")).isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> insert("ACTIVE", "now()", "null")).isInstanceOf(DataAccessException.class);
    assertThatCode(() -> insert("EXPIRED", "null", "now()")).doesNotThrowAnyException();
    assertThatCode(() -> insert("COMMITTED", "now()", "null")).doesNotThrowAnyException();
  }

  @Test void itemQuantityAndSkuAreConstrained() {
    seedStock("SKU-1", 5, 0);
    UUID id = insert("ACTIVE", "null", "null");
    String sql = "insert into stock_reservation_items(reservation_id,sku,quantity) values (?,?,?)";
    assertThatThrownBy(() -> jdbc.update(sql, id, "SKU-1", 0)).isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> jdbc.update(sql, id, "SKU-1", 100)).isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> jdbc.update(sql, id, "NOPE", 1)).isInstanceOf(DataAccessException.class);
    jdbc.update(sql, id, "SKU-1", 99);
    assertThatThrownBy(() -> jdbc.update(sql, id, "SKU-1", 1)).isInstanceOf(DataAccessException.class);
  }

  @Test void runtimeCannotDeleteReservations() {
    UUID id = insert("ACTIVE", "null", "null");
    assertThatThrownBy(() -> jdbc.update("delete from stock_reservations where id=?", id))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> jdbc.update("delete from stock_reservation_items"))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> jdbc.execute("truncate stock_reservation_items, stock_reservations"))
        .isInstanceOf(DataAccessException.class)
        .rootCause().isInstanceOf(java.sql.SQLException.class)
        .extracting(e -> ((java.sql.SQLException)e).getSQLState()).isEqualTo("42501");
  }
}
