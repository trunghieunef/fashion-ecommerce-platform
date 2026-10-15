package vn.fashion.inventory.admin;

import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import vn.fashion.inventory.stock.Sku;
import vn.fashion.inventory.web.Api;

public final class InboundCsv {
  public record Row(int line,String sku,int quantity) { }
  private InboundCsv() { }
  public static List<Row> parse(byte[] file) {
    if(file==null || file.length>128*1024) throw invalid(List.of(new Api.FieldError("file","must be at most 128 KB")));
    String text;
    try { text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(file)).toString(); }
    catch(CharacterCodingException e) { throw invalid(List.of(new Api.FieldError("file","must be UTF-8"))); }
    if(text.startsWith("\uFEFF")) text=text.substring(1);
    String[] lines=text.replace("\r\n","\n").split("\n",-1);int end=lines.length;
    while(end>1 && lines[end-1].isBlank()) end--;
    if(!lines[0].equals("sku,quantity") || end<2 || end>1001)
      throw invalid(List.of(new Api.FieldError("file","requires header sku,quantity and 1..1000 rows")));
    var errors=new ArrayList<Api.FieldError>();var rows=new ArrayList<Row>();var seen=new HashSet<String>();
    for(int i=1;i<end;i++) {
      int line=i+1;String[] cells=lines[i].split(",",-1);
      if(cells.length!=2) { errors.add(new Api.FieldError("rows["+line+"].sku","requires exactly two unquoted cells"));continue; }
      String sku=null;Integer quantity=null;
      try { sku=Sku.canonical(cells[0]); }
      catch(Api.Problem e) { errors.add(new Api.FieldError("rows["+line+"].sku","invalid sku")); }
      if(sku!=null && !seen.add(sku)) errors.add(new Api.FieldError("rows["+line+"].sku","duplicate sku"));
      try {
        String raw=cells[1].strip();if(!raw.matches("[0-9]+")) throw new IllegalArgumentException();
        quantity=Integer.valueOf(raw);if(quantity<1||quantity>1_000_000) throw new IllegalArgumentException();
      } catch(IllegalArgumentException e) { errors.add(new Api.FieldError("rows["+line+"].quantity","must be 1..1000000")); }
      if(sku!=null && quantity!=null) rows.add(new Row(line,sku,quantity));
    }
    if(!errors.isEmpty()) throw invalid(errors);return List.copyOf(rows);
  }
  static Api.Problem invalid(List<Api.FieldError> errors) {
    return new Api.Problem(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","INVALID_CSV",errors);
  }
}
