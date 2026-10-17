package vn.fashion.inventory.reservation;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.*;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import vn.fashion.inventory.stock.Sku;
import vn.fashion.inventory.web.Api;

/** Parses internal reservation bodies after authentication; every failure is a 400 naming the field. */
public final class ReservationRequests {
  public record ReserveCommand(ReservationSnapshot snapshot,String requestHash) { }
  public record ReleaseCommand(UUID orderId,ReservationSnapshot snapshot,String originalRequestHash,String reason) { }
  private ReservationRequests() { }

  public static ReserveCommand reserve(JsonNode body) {
    closed(body,"",Set.of("order_id","order_no","items","expires_at","request_hash"));
    var snap=snapshot(orderId(text(body,"order_id","order_id"),"order_id"),body,"");
    return new ReserveCommand(snap,matching(snap,text(body,"request_hash","request_hash"),"request_hash"));
  }
  public static ReleaseCommand release(JsonNode body) {
    closed(body,"",Set.of("order_id","original_reservation","original_request_hash","reason"));
    UUID order=orderId(text(body,"order_id","order_id"),"order_id");
    var original=body.get("original_reservation");
    closed(original,"original_reservation.",Set.of("order_no","items","expires_at"));
    var snap=snapshot(order,original,"original_reservation.");
    String hash=matching(snap,text(body,"original_request_hash","original_request_hash"),"original_request_hash");
    String reason=text(body,"reason","reason").strip();
    if(reason.isEmpty() || reason.length()>500) throw invalid("reason","must be 1..500 characters");
    return new ReleaseCommand(order,snap,hash,reason);
  }
  public static UUID commit(JsonNode body) {
    closed(body,"",Set.of("order_id"));
    return orderId(text(body,"order_id","order_id"),"order_id");
  }
  public static UUID orderId(String raw,String field) {
    try { UUID id=UUID.fromString(raw); if(id.toString().equals(raw.toLowerCase(Locale.ROOT))) return id; }
    catch(IllegalArgumentException ignored) { }
    throw invalid(field,"must be a UUID");
  }

  private static ReservationSnapshot snapshot(UUID order,JsonNode node,String prefix) {
    String orderNo=text(node,"order_no",prefix+"order_no");
    if(orderNo.isEmpty() || orderNo.length()>32) throw invalid(prefix+"order_no","must be 1..32 characters");
    var raw=node.get("items");
    if(raw==null || !raw.isArray() || raw.isEmpty() || raw.size()>100) throw invalid(prefix+"items","must have 1..100 items");
    var items=new ArrayList<ReservationSnapshot.Item>();
    for(int i=0;i<raw.size();i++) {
      String at=prefix+"items["+i+"].";var item=raw.get(i);
      closed(item,at,Set.of("sku","quantity"));
      String sku;
      try { sku=Sku.canonical(text(item,"sku",at+"sku")); }
      catch(Api.Problem e) { throw invalid(at+"sku","must be 1..64 ASCII SKU characters"); }
      var q=item.get("quantity");
      if(q==null || !q.isIntegralNumber() || !q.canConvertToInt() || q.intValue()<1 || q.intValue()>99)
        throw invalid(at+"quantity","must be an integer 1..99");
      items.add(new ReservationSnapshot.Item(sku,q.intValue()));
    }
    var snap=new ReservationSnapshot(order,orderNo,instant(text(node,"expires_at",prefix+"expires_at"),prefix+"expires_at"),items).normalized();
    if(snap.items().stream().anyMatch(i->i.quantity()>99)) throw invalid(prefix+"items","merged quantity must be at most 99 per SKU");
    return snap;
  }
  private static Instant instant(String raw,String field) {
    try {
      // OpenAPI date-time uses unsigned years 0001..9999; Instant accepts a wider range.
      if(raw.matches("[0-9]{4}-.*Z") && !raw.startsWith("0000-")) {
        var t=Instant.parse(raw); if(t.getNano()%1_000_000==0) return t;
      }
    } catch(DateTimeParseException ignored) { }
    throw invalid(field,"must be a UTC timestamp ending in Z, year 0001..9999, with at most millisecond precision");
  }
  private static String matching(ReservationSnapshot snap,String hash,String field) {
    if(!hash.matches("[0-9a-f]{64}")) throw invalid(field,"must be 64 lowercase hex characters");
    if(!snap.hash().equals(hash)) throw new Api.Problem(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","REQUEST_HASH_MISMATCH",List.of());
    return hash;
  }
  private static void closed(JsonNode node,String prefix,Set<String> allowed) {
    if(node==null || !node.isObject()) throw invalid(prefix.isEmpty()?"body":prefix.substring(0,prefix.length()-1),"must be a JSON object");
    for(String name:node.propertyNames()) if(!allowed.contains(name)) throw invalid(prefix+name,"is not allowed");
  }
  private static String text(JsonNode node,String name,String field) {
    var value=node.get(name);
    if(value==null || !value.isString()) throw invalid(field,"is required");
    return value.asString();
  }
  static Api.Problem invalid(String field,String message) {
    return new Api.Problem(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","INVALID_INPUT",List.of(new Api.FieldError(field,message)));
  }
}
