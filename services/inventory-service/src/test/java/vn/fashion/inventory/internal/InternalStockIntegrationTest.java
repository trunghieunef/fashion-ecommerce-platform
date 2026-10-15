package vn.fashion.inventory.internal;

import static org.assertj.core.api.Assertions.*;
import java.net.http.HttpResponse;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import vn.fashion.inventory.InventoryTestSupport;

class InternalStockIntegrationTest extends InventoryTestSupport {
  @Test void returnsAvailableForAllowedCaller() {
    seedStock("SKU-1",5,2);
    for(String caller:new String[]{"catalog-service","cart-service"}) {
      var r=get("SKU-1",serviceToken(caller));var d=data(r,200);noStore(r);
      assertThat(d.path("sku").asText()).isEqualTo("SKU-1");assertThat(d.path("available").asInt()).isEqualTo(3);
      assertThat(d.path("version").asLong()).isZero();assertThat(d.path("variant_id").asText()).isNotBlank();
      assertThat(Instant.parse(d.path("observed_at").asText())).isBeforeOrEqualTo(Instant.now());
    }
  }
  @Test void lowercaseSkuIsCanonicalized() {
    seedStock("SKU-1",5,2);assertThat(data(get("sku-1",serviceToken("cart-service")),200).path("sku").asText()).isEqualTo("SKU-1");
  }
  @Test void missingTokenIs401AndOtherCallerIs403BeforeValidation() {
    var no=get("BAD@",null);assertThat(no.statusCode()).isEqualTo(401);noStore(no);
    var forbidden=get("BAD@",serviceToken("order-service"));assertThat(forbidden.statusCode()).isEqualTo(403);noStore(forbidden);
    var invalid=get("BAD@",serviceToken("cart-service"));assertThat(invalid.statusCode()).isEqualTo(400);noStore(invalid);
  }
  @Test void unknownSkuIs404() {
    var r=get("UNKNOWN",serviceToken("catalog-service"));assertThat(r.statusCode()).isEqualTo(404);noStore(r);
    assertThat(json(r).path("code").asText()).isEqualTo("NOT_FOUND");
  }
  HttpResponse<String> get(String sku,String token) { return send("GET","/internal/api/v1/inventory/skus/"+sku,token,null,null); }
  void noStore(HttpResponse<String> r) { assertThat(r.headers().firstValue("Cache-Control")).contains("no-store"); }
}
