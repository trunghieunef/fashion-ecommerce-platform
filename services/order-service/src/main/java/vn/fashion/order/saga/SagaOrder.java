package vn.fashion.order.saga;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The order facts every saga step sends (read from order-db; never from a request). */
public record SagaOrder(UUID id, String orderNo, UUID userId, String actorKey, UUID cartId, String method, long total,
                        Instant expiresAt, List<Item> items, UUID quoteId, Map<String, String> toAddress) {
  public record Item(UUID cartItemId, long cartItemVersion, String sku, int quantity) { }
}
