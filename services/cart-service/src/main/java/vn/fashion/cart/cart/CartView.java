package vn.fashion.cart.cart;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Response body (spec section 5); preview is reference only, never a price or stock guarantee. */
public record CartView(UUID cartId, long version, String owner, Instant expiresAt, List<Item> items, String previewStatus) {
  public record Preview(long effectivePrice, boolean priceChanged, boolean active, Integer available, Instant observedAt) { }
  public record Item(UUID itemId, UUID variantId, String sku, int quantity, long priceSnapshot, long itemVersion, Preview preview) { }

  public static CartView of(CartRepository.CartRow cart, List<CartRepository.CartItemRow> items,
                            Map<UUID, Preview> preview, String previewStatus) {
    return new CartView(cart.id(), cart.version(), cart.userId() != null ? "MEMBER" : "GUEST",
        cart.userId() != null ? null : cart.expiresAt(),
        items.stream().map(i -> new Item(i.id(), i.variantId(), i.sku(), i.quantity(), i.priceSnapshot(), i.version(),
            preview.get(i.id()))).toList(), previewStatus);
  }
}
