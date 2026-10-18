package vn.fashion.cart.cart;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vn.fashion.cart.web.Api;

@Repository
public class CartRepository {
  public record CartRow(UUID id, UUID userId, String guestTokenHash, String status, long version, Instant expiresAt) { }
  public record CartItemRow(UUID id, UUID cartId, UUID variantId, String sku, int quantity, long priceSnapshot,
                            long catalogVersion, long version, Instant createdAt) { }
  private static final String OWNED = """
      select id, user_id, guest_token_hash, status, version, expires_at from carts
      where id = :id and status = 'ACTIVE' and (
        (cast(:user as uuid) is not null and user_id = cast(:user as uuid)) or
        (cast(:hash as text) is not null and guest_token_hash = cast(:hash as text) and expires_at > now()))""";
  private final JdbcClient jdbc;
  public CartRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

  /** ACTIVE, owned by the actor and, for guests, not expired; anything else is absent (404). */
  public Optional<CartRow> findOwned(UUID cartId, CartActor actor) {
    return jdbc.sql(OWNED).param("id", cartId).param("user", actor.userId()).param("hash", actor.guestHash())
        .query(CartRow.class).optional();
  }
  /** Row lock for a mutation; same ownership rule as findOwned. Must run inside a transaction. */
  public CartRow lockOwned(UUID cartId, CartActor actor) {
    return jdbc.sql(OWNED + " for update").param("id", cartId).param("user", actor.userId()).param("hash", actor.guestHash())
        .query(CartRow.class).optional().orElseThrow(CartRepository::notFound);
  }
  /** CART-02: the cookie's guest cart while ACTIVE and unexpired, or once MERGED (so a retry can see the merge). */
  public Optional<CartRow> findGuestForMerge(UUID cartId, String guestHash) {
    return jdbc.sql("""
        select id, user_id, guest_token_hash, status, version, expires_at from carts
        where id = :id and guest_token_hash = :hash and (status = 'MERGED' or (status = 'ACTIVE' and expires_at > now()))""")
        .param("id", cartId).param("hash", guestHash).query(CartRow.class).optional();
  }
  public Optional<UUID> mergedInto(UUID cartId) {
    return jdbc.sql("select merged_into from carts where id = :id").param("id", cartId).query(UUID.class).optional()
        .filter(java.util.Objects::nonNull);
  }
  /** Row locks in id order, the one order every multi-cart writer uses (no deadlock). Inside a transaction. */
  public void lockInOrder(UUID a, UUID b) {
    jdbc.sql("select id from carts where id in (:a, :b) order by id for update").param("a", a).param("b", b).query(UUID.class).list();
  }
  public Optional<CartRow> findActive(CartActor actor) {
    return jdbc.sql("""
        select id, user_id, guest_token_hash, status, version, expires_at from carts where status = 'ACTIVE' and (
          (cast(:user as uuid) is not null and user_id = cast(:user as uuid)) or
          (cast(:hash as text) is not null and guest_token_hash = cast(:hash as text) and expires_at > now()))""")
        .param("user", actor.userId()).param("hash", actor.guestHash()).query(CartRow.class).optional();
  }
  /** True when this call created the member's ACTIVE cart; concurrent callers converge on one row. */
  public boolean createMember(UUID userId) {
    return jdbc.sql("""
        insert into carts(id, user_id) values (:id, :user)
        on conflict (user_id) where status = 'ACTIVE' and user_id is not null do nothing""")
        .param("id", UUID.randomUUID()).param("user", userId).update() == 1;
  }
  public CartRow createGuest(String guestHash) {
    UUID id = UUID.randomUUID();
    jdbc.sql("insert into carts(id, guest_token_hash, expires_at) values (:id, :hash, now() + interval '30 days')")
        .param("id", id).param("hash", guestHash).update();
    return findActive(CartActor.guest(guestHash)).orElseThrow();
  }
  /** Sliding guest expiry (spec section 3); members never expire. */
  public void touch(UUID cartId) {
    jdbc.sql("update carts set expires_at = now() + interval '30 days' where id = :id and guest_token_hash is not null")
        .param("id", cartId).update();
  }
  public List<CartItemRow> items(UUID cartId) {
    return jdbc.sql("""
        select id, cart_id, variant_id, sku, quantity, price_snapshot, catalog_version, version, created_at
        from cart_items where cart_id = :cart order by created_at, id""").param("cart", cartId).query(CartItemRow.class).list();
  }
  public static Api.Problem notFound() {
    return new Api.Problem(HttpStatus.NOT_FOUND, "NOT_FOUND", "CART_NOT_FOUND", List.of());
  }
}
