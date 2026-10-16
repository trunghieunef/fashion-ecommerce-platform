package vn.fashion.inventory.reservation;

import static org.assertj.core.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import vn.fashion.inventory.web.Api;

class ReservationRequestsTest {
  static final ObjectMapper JSON=JsonMapper.builder().build();
  static final UUID ORDER=UUID.fromString("99999999-9999-4999-8999-999999999991");
  static String sha(String s) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
  }
  String body(String orderNo,String items,String expires,String hash) {
    return "{\"order_id\":\""+ORDER+"\",\"order_no\":"+JSON.writeValueAsString(orderNo)+",\"items\":"+items
        +",\"expires_at\":\""+expires+"\",\"request_hash\":\""+hash+"\"}";
  }
  ReservationRequests.ReserveCommand parse(String json) { return ReservationRequests.reserve(JSON.readTree(json)); }
  Api.Problem problem(String json) {
    return catchThrowableOfType(Api.Problem.class,()->parse(json));
  }
  String releaseBody(String expires,String hash) {
    return "{\"order_id\":\""+ORDER+"\",\"original_reservation\":{\"order_no\":\"ORD-1\","
        +"\"items\":[{\"sku\":\"SKU-A\",\"quantity\":1}],\"expires_at\":\""+expires
        +"\"},\"original_request_hash\":\""+hash+"\",\"reason\":\"cancel\"}";
  }

  @Test void canonicalMergesSortsUppercasesAndHashes() throws Exception {
    var snap=new ReservationSnapshot(ORDER,"ORD-1",Instant.parse("2026-10-16T03:15:00Z"),
        List.of(new ReservationSnapshot.Item("sku-b",1),new ReservationSnapshot.Item("SKU-A",2),new ReservationSnapshot.Item(" sku-b ",3))).normalized();
    assertThat(snap.canonical()).isEqualTo(ORDER+"|ORD-1|2026-10-16T03:15:00.000Z|SKU-A:2,SKU-B:4");
    assertThat(snap.hash()).isEqualTo(sha(snap.canonical()));
  }
  @Test void escapingPreventsDelimiterCollision() {
    var t=Instant.parse("2026-10-16T03:15:00Z");var items=List.of(new ReservationSnapshot.Item("SKU-A",1));
    var pipe=new ReservationSnapshot(ORDER,"A|B",t,items).normalized();
    var slash=new ReservationSnapshot(ORDER,"A\\|B",t,items).normalized();
    assertThat(pipe.canonical()).contains("|A\\|B|");
    assertThat(slash.canonical()).contains("|A\\\\\\|B|");
    assertThat(pipe.hash()).isNotEqualTo(slash.hash());
  }
  @Test void validReserveParsesWithMatchingHash() {
    var snap=new ReservationSnapshot(ORDER,"ORD-1",Instant.parse("2026-10-16T03:15:00.123Z"),
        List.of(new ReservationSnapshot.Item("SKU-A",2))).normalized();
    var cmd=parse(body("ORD-1","[{\"sku\":\"sku-a\",\"quantity\":2}]","2026-10-16T03:15:00.123Z",snap.hash()));
    assertThat(cmd.snapshot()).isEqualTo(snap);assertThat(cmd.requestHash()).isEqualTo(snap.hash());
  }
  @Test void hashMismatchIs400() {
    var p=problem(body("ORD-1","[{\"sku\":\"SKU-A\",\"quantity\":2}]","2026-10-16T03:15:00Z","0".repeat(64)));
    assertThat(p.status().value()).isEqualTo(400);assertThat(p.getMessage()).isEqualTo("REQUEST_HASH_MISMATCH");
  }
  @Test void unsupportedExpandedYearIs400BeforeHashing() {
    var p=problem(body("ORD-1","[{\"sku\":\"SKU-A\",\"quantity\":1}]",
        "+1000000000-01-01T00:00:00Z","0".repeat(64)));
    assertThat(p.status().value()).isEqualTo(400);
    assertThat(p.code()).isEqualTo("VALIDATION_ERROR");
    assertThat(p.getMessage()).isEqualTo("INVALID_INPUT");
    assertThat(p.errors().get(0).field()).isEqualTo("expires_at");
  }
  @Test void reserveRejectsYearsOutsideContractBeforeHashing() {
    for(String t:List.of("0000-01-01T00:00:00Z","+10000-01-01T00:00:00Z","10000-01-01T00:00:00Z","-0001-01-01T00:00:00Z")) {
      var p=problem(body("ORD-1","[{\"sku\":\"SKU-A\",\"quantity\":1}]",t,"0".repeat(64)));
      assertThat(p.status().value()).isEqualTo(400);
      assertThat(p.code()).isEqualTo("VALIDATION_ERROR");
      assertThat(p.getMessage()).isEqualTo("INVALID_INPUT");
      assertThat(p.errors().get(0).field()).isEqualTo("expires_at");
    }
  }
  @Test void releaseRejectsYearsOutsideContractBeforeHashing() {
    for(String t:List.of("0000-01-01T00:00:00Z","+10000-01-01T00:00:00Z","+1000000000-01-01T00:00:00Z")) {
      var p=catchThrowableOfType(Api.Problem.class,()->ReservationRequests.release(JSON.readTree(releaseBody(t,"0".repeat(64)))));
      assertThat(p.status().value()).isEqualTo(400);
      assertThat(p.code()).isEqualTo("VALIDATION_ERROR");
      assertThat(p.getMessage()).isEqualTo("INVALID_INPUT");
      assertThat(p.errors().get(0).field()).isEqualTo("original_reservation.expires_at");
    }
  }
  @Test void contractYearBoundariesParseWithoutDeadlineValidation() {
    for(String t:List.of("0001-01-01T00:00:00Z","9999-12-31T23:59:59.999Z")) {
      var snap=new ReservationSnapshot(ORDER,"ORD-1",Instant.parse(t),List.of(new ReservationSnapshot.Item("SKU-A",1))).normalized();
      assertThat(parse(body("ORD-1","[{\"sku\":\"SKU-A\",\"quantity\":1}]",t,snap.hash())).snapshot()).isEqualTo(snap);
      assertThat(ReservationRequests.release(JSON.readTree(releaseBody(t,snap.hash()))).snapshot()).isEqualTo(snap);
      assertThat(ReservationSnapshot.time(snap.expiresAt())).startsWith(t.substring(0,4));
    }
  }
  @Test void shapeErrorsNameTheField() {
    String h="0".repeat(64),t="2026-10-16T03:15:00Z",one="[{\"sku\":\"SKU-A\",\"quantity\":1}]";
    assertThat(problem(body("ORD-1","[]",t,h)).errors().get(0).field()).isEqualTo("items");
    assertThat(problem(body("ORD-1","[{\"sku\":\"SKU-A\",\"quantity\":0}]",t,h)).errors().get(0).field()).isEqualTo("items[0].quantity");
    assertThat(problem(body("ORD-1","[{\"sku\":\"SKU-A\",\"quantity\":100}]",t,h)).errors().get(0).field()).isEqualTo("items[0].quantity");
    assertThat(problem(body("ORD-1","[{\"sku\":\"SKU-A\",\"quantity\":60},{\"sku\":\"sku-a\",\"quantity\":40}]",t,h)).errors().get(0).field()).isEqualTo("items");
    assertThat(problem(body("ORD-1","[{\"sku\":\"BAD@\",\"quantity\":1}]",t,h)).errors().get(0).field()).isEqualTo("items[0].sku");
    assertThat(problem(body("x".repeat(33),one,t,h)).errors().get(0).field()).isEqualTo("order_no");
    assertThat(problem(body("ORD-1",one,"2026-10-16T03:15:00.1234Z",h)).errors().get(0).field()).isEqualTo("expires_at");
    assertThat(problem(body("ORD-1",one,"2026-10-16T10:15:00+07:00",h)).errors().get(0).field()).isEqualTo("expires_at");
    assertThat(problem(body("ORD-1",one,t,"A".repeat(64))).errors().get(0).field()).isEqualTo("request_hash");
    var many=new StringBuilder("[");for(int i=0;i<101;i++) many.append(i==0?"":",").append("{\"sku\":\"S").append(i).append("\",\"quantity\":1}");
    assertThat(problem(body("ORD-1",many+"]",t,h)).errors().get(0).field()).isEqualTo("items");
    var extra=body("ORD-1",one,t,h).replace("{\"order_id\"","{\"extra\":1,\"order_id\"");
    assertThat(problem(extra).errors().get(0).field()).isEqualTo("extra");
    assertThat(catchThrowableOfType(Api.Problem.class,()->ReservationRequests.reserve(JSON.readTree("[1]"))).errors().get(0).field()).isEqualTo("body");
  }
  @Test void releaseRecomputesFromOriginalSnapshotWithoutDeadlineCheck() {
    var snap=new ReservationSnapshot(ORDER,"ORD-1",Instant.parse("2000-01-01T00:00:00Z"),List.of(new ReservationSnapshot.Item("SKU-A",1))).normalized();
    var json="{\"order_id\":\""+ORDER+"\",\"original_reservation\":{\"order_no\":\"ORD-1\",\"items\":[{\"sku\":\"SKU-A\",\"quantity\":1}],"
        +"\"expires_at\":\"2000-01-01T00:00:00Z\"},\"original_request_hash\":\""+snap.hash()+"\",\"reason\":\" cancel \"}";
    var cmd=ReservationRequests.release(JSON.readTree(json));
    assertThat(cmd.snapshot()).isEqualTo(snap);assertThat(cmd.reason()).isEqualTo("cancel");
    var wrong=json.replace(snap.hash(),"1".repeat(64));
    assertThat(catchThrowableOfType(Api.Problem.class,()->ReservationRequests.release(JSON.readTree(wrong))).getMessage()).isEqualTo("REQUEST_HASH_MISMATCH");
  }
  @Test void commitNeedsOnlyOrderId() {
    assertThat(ReservationRequests.commit(JSON.readTree("{\"order_id\":\""+ORDER+"\"}"))).isEqualTo(ORDER);
    assertThat(catchThrowableOfType(Api.Problem.class,()->ReservationRequests.commit(JSON.readTree("{\"order_id\":\"nope\"}"))).errors().get(0).field()).isEqualTo("order_id");
  }
}
