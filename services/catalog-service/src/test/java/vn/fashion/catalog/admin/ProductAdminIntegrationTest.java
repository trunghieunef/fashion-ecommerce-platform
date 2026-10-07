package vn.fashion.catalog.admin;

import java.util.Collections;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ProductAdminIntegrationTest extends CatalogAdminTestSupport {
  @Test void createProductIsDraftWithSanitizedDescription() {
    var input = productInput("shirt"); input.put("description_vi", "<p onclick=x>Safe<script>bad</script><img src=x onerror=y></p>");
    var body = data(call("POST", "/products", input), 201);
    assertThat(body.path("status").asText()).isEqualTo("DRAFT");
    assertThat(body.path("version").asLong()).isZero();
    assertThat(body.path("description_vi").asText()).contains("Safe").doesNotContain("script", "onclick", "img");
  }
  @Test void createRequiresExistingCategoryAndBrand() {
    var input = productInput("shirt"); input.put("category_id", UUID.randomUUID()); input.put("brand_id", UUID.randomUUID());
    var r = call("POST", "/products", input);
    assertThat(r.statusCode()).isEqualTo(400);
    assertThat(json(r).path("errors").toString()).contains("category_id", "brand_id");
  }
  @Test void createValidatesPriceTagsAndLengths() {
    var input = productInput("shirt"); input.put("base_price", -1); input.put("tags", Collections.nCopies(21, "x".repeat(51)));
    input.put("description_vi", "x".repeat(20001));
    var r = call("POST", "/products", input);
    assertThat(r.statusCode()).isEqualTo(400);
    assertThat(json(r).path("errors").toString()).contains("base_price", "tags", "description_vi");
    input = productInput("shirt"); input.remove("base_price");
    assertThat(call("POST", "/products", input).statusCode()).isEqualTo(400);
    input.put("base_price", 1.5);
    assertThat(call("POST", "/products", input).statusCode()).isEqualTo(400);
  }
  @Test void basePriceAcceptsLargeIntegerWithoutPrecisionLoss() {
    var input = productInput("shirt"); input.put("base_price", 9007199254740993L);
    var p = data(call("POST", "/products", input), 201);
    assertThat(p.path("base_price").asLong()).isEqualTo(9007199254740993L);
    assertThat(jdbc.queryForObject("select base_price from products where id=?", Long.class, UUID.fromString(p.path("id").asText()))).isEqualTo(9007199254740993L);
  }
  @Test void updateKeepsStatusAndBumpsVersion() {
    UUID id = createProduct("shirt"); var input = productInput("shirt"); input.put("status", "ACTIVE"); input.put("expected_version", 0);
    var p = data(call("PUT", "/products/" + id, input), 200);
    assertThat(p.path("status").asText()).isEqualTo("DRAFT");
    assertThat(p.path("version").asLong()).isEqualTo(1);
  }
  @Test void staleUpdateIs409() {
    UUID id = createProduct("shirt"); var input = productInput("shirt"); input.put("expected_version", 99);
    var r = call("PUT", "/products/" + id, input);
    assertThat(r.statusCode()).isEqualTo(409);
    assertThat(json(r).path("code").asText()).isEqualTo("VERSION_CONFLICT");
  }
  @Test void listPagesWithTotalAndStatusFilter() {
    createProduct("a"); createProduct("b"); createProduct("c");
    var page = data(call("GET", "/products?size=2", null), 200);
    assertThat(page.path("items")).hasSize(2); assertThat(page.path("total").asLong()).isEqualTo(3);
    assertThat(data(call("GET", "/products?status=ACTIVE", null), 200).path("total").asLong()).isZero();
    assertThat(call("GET", "/products?size=101", null).statusCode()).isEqualTo(400);
    assertThat(call("GET", "/products?page=0", null).statusCode()).isEqualTo(400);
  }
  @Test void unknownProductIs404() {
    var response = call("GET", "/products/" + UUID.randomUUID(), null);
    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(json(response).path("code").asText()).isEqualTo("NOT_FOUND");
    assertThat(json(response).path("message").asText()).isEqualTo("PRODUCT_NOT_FOUND");
  }
}
