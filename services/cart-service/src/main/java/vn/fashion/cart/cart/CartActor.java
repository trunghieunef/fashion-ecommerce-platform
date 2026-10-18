package vn.fashion.cart.cart;

import java.util.UUID;

/** Verified cart owner: a member user id or the SHA-256 of a guest cookie (never the raw token). */
public record CartActor(UUID userId, String guestHash) {
  public static CartActor member(UUID userId) { return new CartActor(userId, null); }
  public static CartActor guest(String guestHash) { return new CartActor(null, guestHash); }
  public boolean member() { return userId != null; }
  /** Idempotency scope key (03 section 1.3). */
  public String key() { return member() ? "user:" + userId : "guest:" + guestHash; }
}
