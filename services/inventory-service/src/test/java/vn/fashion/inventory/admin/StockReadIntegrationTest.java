package vn.fashion.inventory.admin;

import static org.assertj.core.api.Assertions.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.fashion.inventory.InventoryTestSupport;

class StockReadIntegrationTest extends InventoryTestSupport {
  @Test void listsSkusWithKeysetAndPrefix() {
    for(String sku:new String[]{"SKU-1","SKU-2","SKU-3","OTHER"}) seedStock(sku,5,2);
    var first=data(call("GET","/skus?sku_prefix=sku-&limit=2",null),200);
    assertThat(first.path("items").size()).isEqualTo(2);
    assertThat(first.path("items").get(0).path("sku").asText()).isEqualTo("SKU-1");
    assertThat(first.path("items").get(0).path("available").asInt()).isEqualTo(3);
    var second=data(call("GET","/skus?sku_prefix=SKU-&limit=2&cursor="+URLEncoder.encode(first.path("next_cursor").asText(),StandardCharsets.UTF_8),null),200);
    assertThat(second.path("items").size()).isEqualTo(1);
    assertThat(second.path("items").get(0).path("sku").asText()).isEqualTo("SKU-3");
    assertThat(second.path("next_cursor").isNull()).isTrue();
  }
  @Test void transactionsNewestFirstAnd404ForUnknown() {
    seedStock("A",0,0);
    UUID a=UUID.randomUUID(),b=UUID.randomUUID();
    jdbc.update("insert into stock_transactions(id,sku,operation_key,type,on_hand_delta,reserved_delta,reason,created_at) values (?,'A','a','ADJUST',1,0,'old',now()-interval '1 hour'),(?,'A','b','ADJUST',1,0,'new',now())",a,b);
    var page=data(call("GET","/skus/a/transactions?limit=1",null),200);
    assertThat(page.path("items").get(0).path("id").asText()).isEqualTo(b.toString());
    var next=data(call("GET","/skus/A/transactions?limit=1&cursor="+page.path("next_cursor").asText(),null),200);
    assertThat(next.path("items").get(0).path("id").asText()).isEqualTo(a.toString());
    assertThat(call("GET","/skus/UNKNOWN/transactions",null).statusCode()).isEqualTo(404);
  }
  @Test void readRequiresInventoryRead() {
    assertThat(send("GET","/admin/api/v1/inventory/skus",token("inventory.adjust"),null,null).statusCode()).isEqualTo(403);
    assertThat(send("GET","/admin/api/v1/inventory/skus",null,null,null).statusCode()).isEqualTo(401);
  }
  @Test void invalidLimitAndCursorReturn400() {
    seedStock("A",0,0);
    for(String path:new String[]{"/skus?limit=0","/skus?limit=abc","/skus?cursor=%%%","/skus/A/transactions?limit=101","/skus/A/transactions?cursor=bad"})
      assertThat(call("GET",path.replace("%%%","%25%25%25"),null).statusCode()).isEqualTo(400);
  }
}
