package vn.fashion.order.orders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import vn.fashion.order.quotes.Quote;

/** Spec section 8: what the owner sees. PENDING is PROCESSING until ORD-02 moves the order on. */
public record OrderView(UUID orderId, String orderNo, String status, String processingStatus, String statusUrl,
                        Integer retryAfterSeconds, long version, String method, List<OrderController.Item> items, Quote.Totals totals,
                        Quote.Address shippingAddress, Quote.Contact contact, String note, Instant createdAt, Instant expiresAt,
                        List<String> allowedActions) {
  public boolean processing() { return "PROCESSING".equals(processingStatus); }
}
