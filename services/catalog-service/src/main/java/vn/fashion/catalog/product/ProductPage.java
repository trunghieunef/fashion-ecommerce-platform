package vn.fashion.catalog.product;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record ProductPage(List<ProductSummary> items, @JsonProperty("next_cursor") String nextCursor) {
}
