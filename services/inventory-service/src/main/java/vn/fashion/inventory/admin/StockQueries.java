package vn.fashion.inventory.admin;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import vn.fashion.inventory.stock.*;

@Repository
public class StockQueries {
  public record Page<T>(List<T> items,String nextCursor) { }
  public record Ledger(UUID id,String type,int onHandDelta,int reservedDelta,String reason,UUID actorId,UUID orderId,Instant createdAt) { }
  private final JdbcClient jdbc;
  private final ObjectMapper json;
  public StockQueries(JdbcClient jdbc,ObjectMapper json) { this.jdbc=jdbc;this.json=json; }
  public StockRow stock(String sku) {
    return jdbc.sql("select * from stock_items where sku=:sku").param("sku",sku).query(StockRow.class).optional().orElseThrow(StockAdminService::notFound);
  }
  public Page<Map<String,Object>> list(String prefix,String rawLimit,String cursor) {
    int limit=limit(rawLimit);
    String p=prefix==null?"":prefix.strip();
    if(!p.matches("[A-Za-z0-9._-]{0,64}")) throw StockAdminService.invalid("sku_prefix","invalid prefix");
    p=p.toUpperCase(Locale.ROOT);String after="";
    if(cursor!=null) {
      after=decode(cursor);
      if(!Sku.valid(after)) throw StockAdminService.invalid("cursor","invalid cursor");
    }
    var rows=jdbc.sql("select * from stock_items where starts_with(sku,:prefix) and sku>:after order by sku limit :limit")
        .param("prefix",p).param("after",after).param("limit",limit+1).query(StockRow.class).list();
    boolean more=rows.size()>limit;var page=rows.subList(0,Math.min(limit,rows.size()));
    return new Page<>(page.stream().map(StockQueries::view).toList(),more?encode(page.getLast().sku()):null);
  }
  public Page<Ledger> transactions(String rawSku,String rawLimit,String cursor) {
    String sku=Sku.canonical(rawSku);stock(sku);int limit=limit(rawLimit);
    Instant t=Instant.parse("9999-12-31T23:59:59.999999Z");UUID id=UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
    if(cursor!=null) {
      try {
        var n=json.readTree(decode(cursor));
        if(!n.isObject() || n.size()!=2 || !n.path("t").isString() || !n.path("id").isString()) throw new IllegalArgumentException();
        t=Instant.parse(n.path("t").asText());int year=t.atOffset(ZoneOffset.UTC).getYear();
        if(year<1 || year>9999) throw new IllegalArgumentException();
        String raw=n.path("id").asText();id=UUID.fromString(raw);
        if(!id.toString().equalsIgnoreCase(raw)) throw new IllegalArgumentException();
      } catch(RuntimeException e) { throw StockAdminService.invalid("cursor","invalid cursor"); }
    }
    var rows=jdbc.sql("select * from stock_transactions where sku=:sku and (created_at,id)<(:t,:id) order by created_at desc,id desc limit :limit")
        .param("sku",sku).param("t",OffsetDateTime.ofInstant(t,ZoneOffset.UTC)).param("id",id).param("limit",limit+1).query(Ledger.class).list();
    boolean more=rows.size()>limit;var page=rows.subList(0,Math.min(limit,rows.size()));
    var last=page.isEmpty()?null:page.getLast();
    return new Page<>(List.copyOf(page),more?encode(json.writeValueAsString(Map.of("t",last.createdAt().toString(),"id",last.id()))):null);
  }
  public static Map<String,Object> view(StockRow row) {
    return Map.of("sku",row.sku(),"variant_id",row.variantId(),"product_id",row.productId(),"on_hand",row.onHand(),
        "reserved",row.reserved(),"available",row.available(),"min_stock",row.minStock(),"status",row.status(),"version",row.version());
  }
  static int limit(String raw) {
    try { int n=raw==null?20:Integer.parseInt(raw);if(n<1||n>100) throw new IllegalArgumentException();return n; }
    catch(IllegalArgumentException e) { throw StockAdminService.invalid("limit","must be 1..100"); }
  }
  String encode(String text) { return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8)); }
  String decode(String text) {
    try { if(text.isEmpty()||text.length()>1024) throw new IllegalArgumentException();return new String(Base64.getUrlDecoder().decode(text),StandardCharsets.UTF_8); }
    catch(IllegalArgumentException e) { throw StockAdminService.invalid("cursor","invalid cursor"); }
  }
}
