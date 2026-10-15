package vn.fashion.inventory.admin;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import vn.fashion.inventory.stock.*;
import vn.fashion.inventory.web.Api;

@Service
public class StockAdminService {
  public record AdjustRequest(String sku,Integer delta,String reason) { }
  private final JdbcClient jdbc;
  private final AdminCommands commands;
  private final StockEvents events;
  public StockAdminService(JdbcClient jdbc,AdminCommands commands,StockEvents events) {
    this.jdbc=jdbc;this.commands=commands;this.events=events;
  }
  public AdminCommands.Replay adjust(UUID actor,String key,AdjustRequest req,String traceId) {
    AdminCommands.requireKey(key);
    if(req==null) throw invalid("body","is required");
    String sku=Sku.canonical(req.sku());
    if(req.delta()==null || req.delta()==0) throw invalid("delta","must be a nonzero integer");
    String reason=reason(req.reason());
    return commands.create(actor,"inventory.adjust",key,new AdjustRequest(sku,req.delta(),reason),()->{
      var row=lockForUpdate(List.of(sku)).get(sku);
      if(row==null) throw notFound();
      StockRow after;
      try { after=applyDelta(row,req.delta(),"ADJUST",key,reason,actor,traceId); }
      catch(ArithmeticException e) { throw invalid("delta","exceeds stock integer range"); }
      return new AdminCommands.Result(null,200,StockQueries.view(after));
    });
  }
  public Map<String,StockRow> lockForUpdate(List<String> sortedSkus) {
    var rows=jdbc.sql("select * from stock_items where sku = any(:skus) order by sku for update")
        .param("skus",sortedSkus.toArray(String[]::new)).query(StockRow.class).list();
    var result=new LinkedHashMap<String,StockRow>();for(var row:rows) result.put(row.sku(),row);return result;
  }
  public StockRow applyDelta(StockRow row,int delta,String type,String key,String reason,UUID actor,String traceId) {
    int next=Math.addExact(row.onHand(),delta);
    if(next<row.reserved()) throw new Api.Problem(HttpStatus.CONFLICT,"CONFLICT","ON_HAND_BELOW_RESERVED",List.of());
    var after=jdbc.sql("update stock_items set on_hand=:next,version=version+1,updated_at=now() where sku=:sku returning *")
        .param("next",next).param("sku",row.sku()).query(StockRow.class).single();
    String operation=type.equals("INBOUND")?"inventory.import":"inventory.adjust";
    String operationKey="user:"+actor+":"+operation+":"+key;
    jdbc.sql("""
        insert into stock_transactions(id,sku,operation_key,type,on_hand_delta,reserved_delta,reason,actor_id)
        values (:id,:sku,:key,:type,:delta,0,:reason,:actor)
        """).param("id",UUID.randomUUID()).param("sku",row.sku()).param("key",operationKey)
        .param("type",type).param("delta",delta).param("reason",reason).param("actor",actor).update();
    events.append(after,row.available()==0 && after.available()>0,traceId);return after;
  }
  static String reason(String raw) {
    if(raw==null || raw.strip().isEmpty() || raw.strip().length()>500) throw invalid("reason","must be 1..500 characters");
    return raw.strip();
  }
  static Api.Problem invalid(String field,String message) {
    return new Api.Problem(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","INVALID_INPUT",List.of(new Api.FieldError(field,message)));
  }
  static Api.Problem notFound() { return new Api.Problem(HttpStatus.NOT_FOUND,"NOT_FOUND","SKU_NOT_FOUND",List.of()); }
}
