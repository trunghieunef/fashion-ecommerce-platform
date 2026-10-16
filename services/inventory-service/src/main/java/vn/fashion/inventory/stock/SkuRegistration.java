package vn.fashion.inventory.stock;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import vn.fashion.platform.messaging.EventEnvelope;
import vn.fashion.platform.messaging.PermanentEventException;

@Component
public class SkuRegistration {
  private final JdbcClient jdbc;
  private final StockEvents events;
  public SkuRegistration(JdbcClient jdbc,StockEvents events) { this.jdbc=jdbc;this.events=events; }
  public Boolean register(EventEnvelope env) {
    var p=env.payload();
    if(env.version()!=1 || p.size()!=4 || !p.path("version").isIntegralNumber()
        || !p.path("version").canConvertToLong() || p.path("version").longValue()<0) throw invalid();
    UUID product=uuid(p,"product_id"),variant=uuid(p,"variant_id");
    if(!p.path("sku").isString()) throw invalid();
    String sku;
    try { sku=Sku.canonical(p.path("sku").asText()); }
    catch(vn.fashion.inventory.web.Api.Problem e) { throw invalid(); }
    if(!Sku.valid(sku)) throw invalid();
    int inserted=jdbc.sql("insert into stock_items(sku,product_id,variant_id) values (:sku,:product,:variant) on conflict do nothing")
        .param("sku",sku).param("product",product).param("variant",variant).update();
    var rows=jdbc.sql("select * from stock_items where sku=:sku or variant_id=:variant order by sku for update")
        .param("sku",sku).param("variant",variant).query(StockRow.class).list();
    if(rows.size()!=1 || !rows.getFirst().sku().equals(sku) || !rows.getFirst().variantId().equals(variant))
      throw new PermanentEventException("sku/variant mismatch");
    if(inserted==1) events.append(rows.getFirst(),false,env.correlationId());
    return inserted==1;
  }
  private UUID uuid(JsonNode p,String field) {
    try {
      if(!p.path(field).isString()) throw invalid();
      String raw=p.path(field).asText(); UUID id=UUID.fromString(raw);
      if(!id.toString().equalsIgnoreCase(raw)) throw invalid(); return id;
    } catch(IllegalArgumentException e) { throw invalid(); }
  }
  private static PermanentEventException invalid() { return new PermanentEventException("Invalid VARIANT_CREATED payload"); }
}
