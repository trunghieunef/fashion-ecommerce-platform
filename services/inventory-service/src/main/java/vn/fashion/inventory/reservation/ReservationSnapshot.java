package vn.fashion.inventory.reservation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import vn.fashion.inventory.stock.Sku;

/** Normalized order snapshot; request_hash is computed here, never trusted from the caller (03 section 1.3). */
public record ReservationSnapshot(UUID orderId,String orderNo,Instant expiresAt,List<Item> items) {
  public record Item(String sku,int quantity) { }
  private static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
  public static String time(Instant t) { return TIME.format(t); }
  /** Canonical SKUs merged (quantities summed) and sorted; quantity limits are checked by the caller. */
  public ReservationSnapshot normalized() {
    var merged=new TreeMap<String,Integer>();
    for(var item:items) merged.merge(Sku.canonical(item.sku()),item.quantity(),Integer::sum);
    return new ReservationSnapshot(orderId,orderNo,expiresAt,
        merged.entrySet().stream().map(e->new Item(e.getKey(),e.getValue())).toList());
  }
  public List<String> skus() { return items.stream().map(Item::sku).toList(); }
  public String canonical() {
    String escaped=orderNo.replace("\\","\\\\").replace("|","\\|");
    return orderId+"|"+escaped+"|"+time(expiresAt)+"|"
        +items.stream().map(i->i.sku()+":"+i.quantity()).collect(Collectors.joining(","));
  }
  public String hash() {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical().getBytes(StandardCharsets.UTF_8))); }
    catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
  }
}
