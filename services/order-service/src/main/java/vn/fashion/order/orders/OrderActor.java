package vn.fashion.order.orders;

import java.util.UUID;

/** Spec section 4: a member (Bearer) or a guest proven by a cart checkout token bound to tokenCartId. */
public record OrderActor(UUID userId, String guestHash, UUID tokenCartId) {
  public static OrderActor member(UUID userId) { return new OrderActor(userId, null, null); }
  public static OrderActor guest(String guestHash, UUID cartId) { return new OrderActor(null, guestHash, cartId); }
  public boolean member() { return userId != null; }
  /** Same value as the CART-03 Cart-Actor header. */
  public String key() { return member() ? "user:" + userId : "guest:" + guestHash; }
}
