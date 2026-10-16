package vn.fashion.inventory.admin;

import static org.assertj.core.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import vn.fashion.inventory.web.Api;

class InboundCsvTest {
  @Test void acceptsBomAndCrlf() {
    var rows=InboundCsv.parse(bytes("\uFEFFsku,quantity\r\na,1\r\n"));
    assertThat(rows).containsExactly(new InboundCsv.Row(2,"A",1));
  }
  @Test void rejectsWrongHeader() { assertThat(problem("SKU,quantity\nA,1").errors().getFirst().field()).isEqualTo("file"); }
  @Test void rejectsDuplicateSku() { assertThat(problem("sku,quantity\na,1\nA,2").errors().getFirst().field()).isEqualTo("rows[3].sku"); }
  @Test void rejects1001Rows() {
    StringBuilder b=new StringBuilder("sku,quantity\n");for(int i=0;i<1001;i++) b.append("SKU-").append(i).append(",1\n");
    assertThat(problem(b.toString()).errors().getFirst().field()).isEqualTo("file");
  }
  @Test void reportsEveryBadLine() {
    var p=problem("sku,quantity\nA,0\nB,1000001");
    assertThat(p.code()).isEqualTo("VALIDATION_ERROR");assertThat(p.getMessage()).isEqualTo("INVALID_CSV");
    assertThat(p.errors()).extracting(Api.FieldError::field).containsExactly("rows[2].quantity","rows[3].quantity");
  }
  @Test void rejectsMalformedUtf8AndOversizeAndEmptyFile() {
    for(byte[] input:new byte[][]{new byte[]{(byte)0xc3,0x28},new byte[128*1024+1],bytes("sku,quantity\n")})
      assertThatThrownBy(()->InboundCsv.parse(input)).isInstanceOf(Api.Problem.class);
  }
  @Test void quotesAndBadShapeAreRejected() {
    for(String line:new String[]{"\"A\",1","A,1,2","A,no","A,2147483648","A,-1"}) assertThat(problem("sku,quantity\n"+line).errors()).isNotEmpty();
  }
  Api.Problem problem(String body) {
    try { InboundCsv.parse(bytes(body));throw new AssertionError("CSV should be invalid"); }
    catch(Api.Problem e) { return e; }
  }
  byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }
}
