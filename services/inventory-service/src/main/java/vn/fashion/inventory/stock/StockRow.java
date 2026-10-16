package vn.fashion.inventory.stock;

import java.time.Instant;
import java.util.UUID;

public record StockRow(String sku, UUID productId, UUID variantId, int onHand, int reserved,
    int minStock, String status, long version, Instant updatedAt) {
  public int available() { return onHand-reserved; }
}
