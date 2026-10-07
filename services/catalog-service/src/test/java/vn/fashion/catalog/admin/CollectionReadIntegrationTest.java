package vn.fashion.catalog.admin;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CollectionReadIntegrationTest extends CatalogAdminTestSupport {
  private UUID seed(String status, long version) {
    UUID id = UUID.randomUUID();
    jdbc.update("insert into collections(id,name_vi,name_en,slug,status,version) values (?,'Bộ sưu tập','Collection',?,?,?)", id, id.toString(), status, version);
    return id;
  }
  private void item(UUID collection, UUID product, int sort) {
    jdbc.update("insert into products(id,slug,name_vi,name_en,status,category_id,base_price) values (?,?,'Mẫu','Sample','DRAFT',?,0)", product, product.toString(), SEED);
    jdbc.update("insert into collection_items(collection_id,product_id,sort_order) values (?,?,?)", collection, product, sort);
  }
  @Test void collectionGetsRequireCatalogWrite() {
    for (String suffix : new String[]{"", "/" + UUID.randomUUID()}) {
      String path = "/admin/api/v1/catalog/collections" + suffix;
      data(send("GET", path, null, null, null), 401);
      data(send("GET", path, token("member"), null, null), 403);
    }
  }
  @Test void detailIsOrderedAndHasEditVersion() {
    UUID c = seed("DRAFT", 7);
    UUID a = UUID.fromString("7fffffff-ffff-4fff-8fff-ffffffffffff"), b = UUID.fromString("80000000-0000-4000-8000-000000000002"), small = UUID.fromString("00000000-0000-4000-8000-000000000003");
    item(c, small, 1); item(c, b, 0); item(c, a, 0);
    var result = data(call("GET", "/collections/" + c, null), 200);
    assertThat(result.path("id").asText()).isEqualTo(c.toString());
    assertThat(result.path("version").asLong()).isEqualTo(7);
    assertThat(result.path("status").asText()).isEqualTo("DRAFT");
    assertThat(result.path("name_vi").asText()).isEqualTo("Bộ sưu tập");
    assertThat(result.path("name_en").asText()).isEqualTo("Collection");
    assertThat(result.path("slug").asText()).isEqualTo(c.toString());
    assertThat(result.path("cover_url").isNull()).isTrue();
    assertThat(result.path("start_at").isNull()).isTrue(); assertThat(result.path("end_at").isNull()).isTrue();
    assertThat(result.path("items").size()).isEqualTo(3);
    assertThat(result.path("items").get(0).path("product_id").asText()).isEqualTo(a.toString());
    assertThat(result.path("items").get(1).path("product_id").asText()).isEqualTo(b.toString());
    assertThat(result.path("items").get(2).path("product_id").asText()).isEqualTo(small.toString());
    assertThat(result.path("items").get(2).path("sort_order").asInt()).isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from audit_logs", Integer.class)).isZero();
  }
  @Test void emptyInactiveCollectionReturnsEmptyItems() {
    var result = data(call("GET", "/collections/" + seed("INACTIVE", 3), null), 200);
    assertThat(result.path("items").isArray()).isTrue(); assertThat(result.path("items").size()).isZero();
    assertThat(result.path("status").asText()).isEqualTo("INACTIVE");
  }
  @Test void missingCollectionIs404() {
    var response = call("GET", "/collections/" + UUID.randomUUID(), null);
    data(response, 404); assertThat(json(response).path("code").asText()).isEqualTo("NOT_FOUND");
  }
  @Test void listHasPaginationStatusAndSummaries() {
    UUID a = seed("DRAFT", 0), b = seed("INACTIVE", 2), c = seed("ACTIVE", 4);
    jdbc.update("update collections set created_at='2026-10-07T00:00:00Z'");
    var order = jdbc.queryForList("select id from collections order by created_at desc,id desc", UUID.class);
    var result = data(call("GET", "/collections?size=2", null), 200);
    assertThat(result.path("page").asInt()).isEqualTo(1); assertThat(result.path("size").asInt()).isEqualTo(2); assertThat(result.path("total").asLong()).isEqualTo(3);
    assertThat(result.path("items").size()).isEqualTo(2);
    assertThat(result.path("items").get(0).has("items")).isFalse();
    assertThat(result.path("items").get(0).path("id").asText()).isEqualTo(order.get(0).toString());
    assertThat(result.path("items").get(1).path("id").asText()).isEqualTo(order.get(1).toString());
    assertThat(data(call("GET", "/collections?page=2&size=2", null), 200).path("items").get(0).path("id").asText()).isEqualTo(order.get(2).toString());
    var filtered = data(call("GET", "/collections?status=INACTIVE", null), 200);
    assertThat(filtered.path("total").asLong()).isEqualTo(1); assertThat(filtered.path("items").get(0).path("id").asText()).isEqualTo(b.toString());
    assertThat(data(call("GET", "/collections", null), 200).path("size").asInt()).isEqualTo(20);
    jdbc.update("update collections set created_at='2026-10-08T00:00:00Z' where id=?", a);
    assertThat(data(call("GET", "/collections", null), 200).path("items").get(0).path("id").asText()).isEqualTo(a.toString());
  }
  @Test void invalidPageSizeAndStatusAre400() {
    for (String query : new String[]{"page=0", "size=0", "size=101", "status=UNKNOWN"}) {
      var response = call("GET", "/collections?" + query, null);
      data(response, 400); assertThat(json(response).path("code").asText()).isEqualTo("VALIDATION_ERROR");
    }
  }
}
