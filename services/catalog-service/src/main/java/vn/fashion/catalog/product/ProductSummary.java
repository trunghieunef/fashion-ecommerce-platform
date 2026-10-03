package vn.fashion.catalog.product;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

public record ProductSummary(UUID id, String slug, @JsonProperty("name_vi") String nameVi,
                             @JsonProperty("name_en") String nameEn) {
}
