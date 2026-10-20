package vn.fashion.order.quotes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Spec section 5: everything the order needs from a confirmed quote, carried in the signed quote_token (not stored).
 * Only the customer's own input and public prices; no secret.
 */
public record Quote(String actorKey, UUID cartId, long cartVersion, String method, Address shippingAddress, Contact contact,
                    String note, String locale, List<Line> items, Shipping shipping, Totals totals, Instant expiresAt) {
  public record Address(String recipientName, String phone, String provinceCode, String wardCode, String addressLine) { }
  public record Contact(String phone, String email) { }
  public record Line(UUID variantId, UUID cartItemId, long cartItemVersion, int quantity, long unitPrice, long lineTotal,
                     long productVersion, String sku, String name, String size, String color, String imageUrl) { }
  public record Shipping(UUID quoteId, String carrier, int weightGrams, long fee, long codFee, Instant expiresAt) { }
  public record Totals(long subtotal, long discountAmount, long shippingFee, long shippingDiscount, long codFee, long totalAmount,
                       String currency) { }

  public boolean expired(Instant now) { return !now.isBefore(expiresAt); }
}
