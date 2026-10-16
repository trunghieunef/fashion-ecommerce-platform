package vn.fashion.inventory.admin;

import static org.assertj.core.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import vn.fashion.inventory.InventoryTestSupport;

class StockImportIntegrationTest extends InventoryTestSupport {
  @Test void invalidCsvRollsBackEveryRow() {
    seedStock("SKU-1",1,0);var r=upload("sku,quantity\nSKU-1,5\nUNKNOWN,4","in","bad");
    assertThat(r.statusCode()).isEqualTo(400);
    assertThat(json(r).path("errors").get(0).path("field").asText()).isEqualTo("rows[3].sku");
    assertThat(jdbc.queryForObject("select on_hand from stock_items where sku='SKU-1'",Integer.class)).isEqualTo(1);assertNoWrites();
  }
  @Test void importAddsInboundForEachRow() {
    seedStock("A",1,0);seedStock("B",0,0);var d=data(upload("sku,quantity\nB,4\nA,5"," delivery ","inbound"),200);
    assertThat(d.path("rows").asInt()).isEqualTo(2);assertThat(d.path("total_quantity").asInt()).isEqualTo(9);
    assertThat(jdbc.queryForList("select on_hand from stock_items order by sku",Integer.class)).containsExactly(6,4);
    assertThat(jdbc.queryForList("select type from stock_transactions",String.class)).containsExactly("INBOUND","INBOUND");
    assertThat(jdbc.queryForObject("select count(*) from outbox_events where event_type='INVENTORY_UPDATED'",Integer.class)).isEqualTo(2);
    assertThat(jdbc.queryForObject("select aggregate_id from outbox_events where event_type='INVENTORY_RESTOCKED'",String.class)).isEqualTo("B");
  }
  @Test void replayDoesNotAddTwice() {
    seedStock("A",0,0);String file="sku,quantity\nA,5\n";
    assertThat(data(upload(file,"in","same"),200)).isEqualTo(data(upload(file,"in","same"),200));
    assertThat(jdbc.queryForObject("select on_hand from stock_items where sku='A'",Integer.class)).isEqualTo(5);
    assertThat(jdbc.queryForObject("select count(*) from stock_transactions",Integer.class)).isEqualTo(1);
    assertThat(upload(file.replace("5","6"),"in","same").statusCode()).isEqualTo(409);
  }
  @Test void importHashHasStableFieldOrderAndReplay() throws Exception {
    seedStock("A",0,0);String file="sku,quantity\nA,5\n";
    var first=data(upload(file," in ","stable-hash"),200);
    String fileHash=sha256(file);
    String canonical="{\"file_sha256\":\""+fileHash+"\",\"reason\":\"in\"}";
    assertThat(jdbc.queryForObject("select request_hash from idempotency_requests where key='stable-hash'",String.class))
        .isEqualTo(sha256(canonical));
    assertThat(data(upload(file,"in","stable-hash"),200)).isEqualTo(first);
    assertThat(jdbc.queryForObject("select on_hand from stock_items where sku='A'",Integer.class)).isEqualTo(5);
    assertThat(jdbc.queryForObject("select count(*) from stock_transactions",Integer.class)).isEqualTo(1);
  }
  static String sha256(String value) throws Exception {
    return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.getBytes(StandardCharsets.UTF_8)));
  }
  @Test void oversizedFileIs400Or413() {
    var r=upload("x".repeat(129*1024),"in","oversize");
    assertThat(r.statusCode()).isEqualTo(413);assertNoWrites();
  }
  @Test void requiresAdjustPermissionAndKey() {
    String csv="sku,quantity\nA,1";
    assertThat(multipart(csv,"in","key",null).statusCode()).isEqualTo(401);
    assertThat(multipart(csv,"in","key",token("inventory.read")).statusCode()).isEqualTo(403);
    assertThat(multipart(csv,"in",null,token("inventory.adjust")).statusCode()).isEqualTo(400);
    assertThat(upload(csv," ","blank").statusCode()).isEqualTo(400);assertNoWrites();
  }
  @Test void adjustAndImportCanReuseKeyForSameSku() {
    seedStock("A",0,0);
    data(send("POST","/admin/api/v1/inventory/adjust",token("inventory.adjust"),"shared","{\"sku\":\"A\",\"delta\":1,\"reason\":\"in\"}"),200);
    data(upload("sku,quantity\nA,2","in","shared"),200);
    assertThat(jdbc.queryForObject("select on_hand from stock_items where sku='A'",Integer.class)).isEqualTo(3);
    assertThat(jdbc.queryForObject("select count(distinct operation_key) from stock_transactions",Integer.class)).isEqualTo(2);
  }
  @Test void overflowInLaterRowRollsBackEverything() {
    seedStock("A",0,0);seedStock("B",Integer.MAX_VALUE,0);
    var r=upload("sku,quantity\nA,1\nB,1","in","overflow");assertThat(r.statusCode()).isEqualTo(400);
    assertThat(json(r).path("errors").get(0).path("field").asText()).isEqualTo("rows[3].quantity");
    assertThat(jdbc.queryForObject("select on_hand from stock_items where sku='A'",Integer.class)).isZero();assertNoWrites();
  }
  HttpResponse<String> upload(String csv,String reason,String key) { return multipart(csv,reason,key,token("inventory.adjust")); }
  HttpResponse<String> multipart(String csv,String reason,String key,String token) {
    try {
      String boundary="boundary-"+UUID.randomUUID();var bytes=new ByteArrayOutputStream();
      bytes.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"inbound.csv\"\r\nContent-Type: text/csv\r\n\r\n").getBytes(StandardCharsets.UTF_8));
      bytes.write(csv.getBytes(StandardCharsets.UTF_8));
      bytes.write(("\r\n--"+boundary+"\r\nContent-Disposition: form-data; name=\"reason\"\r\n\r\n"+reason+"\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
      var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/admin/api/v1/inventory/import"))
          .header("Content-Type","multipart/form-data; boundary="+boundary).POST(HttpRequest.BodyPublishers.ofByteArray(bytes.toByteArray()));
      if(token!=null) request.header("Authorization","Bearer "+token);if(key!=null) request.header("Idempotency-Key",key);
      return http.send(request.build(),HttpResponse.BodyHandlers.ofString());
    } catch(Exception e) { throw new AssertionError(e); }
  }
  void assertNoWrites() {
    assertThat(jdbc.queryForObject("select count(*) from stock_transactions",Integer.class)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from outbox_events",Integer.class)).isZero();
  }
}
