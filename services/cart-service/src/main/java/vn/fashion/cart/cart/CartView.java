package vn.fashion.cart.cart;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Response body (spec section 5); preview is reference only, never a price or stock guarantee. CART-03 section 5:
 * subtotal_preview is a labeled estimate and the shipping fee is never computed here, so no total is final.
 */
public record CartView(UUID cartId, long version, String owner, Instant expiresAt, List<Item> items, String previewStatus,
                       Long subtotalPreview, String shippingFeeStatus) {
  public record Preview(long effectivePrice, boolean priceChanged, boolean active, Integer available, Instant observedAt) { }
  public record Item(UUID itemId, UUID variantId, String sku, int quantity, long priceSnapshot, long itemVersion, Preview preview) { }

  public static CartView of(CartRepository.CartRow cart, List<CartRepository.CartItemRow> items,
                            Map<UUID, Preview> preview, String previewStatus) {
    var views = items.stream().map(i -> new Item(i.id(), i.variantId(), i.sku(), i.quantity(), i.priceSnapshot(), i.version(),
        preview.get(i.id()))).toList();
    return new CartView(cart.id(), cart.version(), cart.userId() != null ? "MEMBER" : "GUEST",
        cart.userId() != null ? null : cart.expiresAt(), views, previewStatus,
        "OK".equals(previewStatus) ? subtotal(views) : null, "NOT_CALCULATED");
  }

  /** VND long with overflow check; an overflow hides the estimate instead of failing the read. */
  private static Long subtotal(List<Item> items) {
    try {
      long sum = 0;
      for (var item : items)
        if (item.preview() != null && item.preview().active()) sum = Math.addExact(sum, Math.multiplyExact(item.preview().effectivePrice(), item.quantity()));
      return sum;
    } catch (ArithmeticException e) { return null; }
  }
}
