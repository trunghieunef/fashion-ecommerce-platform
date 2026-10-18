package vn.fashion.cart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;

class CartSchemaIntegrationTest extends CartTestSupport {
  UUID cart(UUID user, String hash, String status) {
    UUID id = UUID.randomUUID();
    jdbc.update("insert into carts(id,user_id,guest_token_hash,status) values (?,?,?,?)", id, user, hash, status);
    return id;
  }
  void item(UUID cart, UUID variant, int quantity) {
    jdbc.update("insert into cart_items(id,cart_id,variant_id,sku,quantity,price_snapshot,catalog_version) values (?,?,?,'S',?,1,1)",
        UUID.randomUUID(), cart, variant, quantity);
  }
  @Test void exactlyOneOwner() {
    assertThatThrownBy(() -> cart(null, null, "ACTIVE")).isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> cart(UUID.randomUUID(), "a".repeat(64), "ACTIVE")).isInstanceOf(DataIntegrityViolationException.class);
  }
  @Test void oneActiveCartPerMember() {
    UUID user = UUID.randomUUID();
    cart(user, null, "ACTIVE");
    cart(user, null, "MERGED");
    assertThatThrownBy(() -> cart(user, null, "ACTIVE")).isInstanceOf(DataIntegrityViolationException.class);
  }
  @Test void itemQuantityBounds() {
    UUID c = cart(UUID.randomUUID(), null, "ACTIVE");
    for (int q : new int[] {0, 100}) {
      assertThatThrownBy(() -> item(c, UUID.randomUUID(), q)).isInstanceOf(DataIntegrityViolationException.class);
    }
  }
  @Test void variantUniquePerCart() {
    UUID c = cart(UUID.randomUUID(), null, "ACTIVE"), v = UUID.randomUUID();
    item(c, v, 1);
    assertThatThrownBy(() -> item(c, v, 1)).isInstanceOf(DataIntegrityViolationException.class);
  }
  @Test void runtimeCannotDeleteCarts() {
    UUID c = cart(UUID.randomUUID(), null, "ACTIVE");
    assertThatThrownBy(() -> jdbc.update("delete from carts where id=?", c)).isInstanceOf(DataAccessException.class);
  }
  @Test void readinessUp() {
    assertThat(send("GET", "/actuator/health/readiness", null).statusCode()).isEqualTo(200);
  }
}
