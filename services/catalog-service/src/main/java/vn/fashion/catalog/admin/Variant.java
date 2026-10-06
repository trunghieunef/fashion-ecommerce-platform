package vn.fashion.catalog.admin;

import java.util.UUID;

public record Variant(UUID id, UUID productId, String sku, String size, String color, Long priceOverride,
                      int weightGrams, String status, long version) { }
