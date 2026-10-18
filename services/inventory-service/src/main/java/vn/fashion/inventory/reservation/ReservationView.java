package vn.fashion.inventory.reservation;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ReservationView(UUID reservationId,UUID orderId,String orderNo,String status,String expiresAt,
    List<Item> items,long version,String committedAt,String releasedAt,String releaseReason,
    boolean restoreRequired) {
  /** returned: INV-03 total restored per SKU (0 until a return). */
  public record Item(String sku,int quantity,int returned) { }
  static ReservationView of(ReservationRow r,List<ReservationSnapshot.Item> items,boolean restoreRequired) {
    return of(r,items,restoreRequired,Map.of());
  }
  static ReservationView of(ReservationRow r,List<ReservationSnapshot.Item> items,boolean restoreRequired,Map<String,Integer> returned) {
    return new ReservationView(r.id(),r.orderId(),r.orderNo(),r.status(),ReservationSnapshot.time(r.expiresAt()),
        items.stream().map(i->new Item(i.sku(),i.quantity(),returned.getOrDefault(i.sku(),0))).toList(),r.version(),
        r.committedAt()==null?null:ReservationSnapshot.time(r.committedAt()),
        r.releasedAt()==null?null:ReservationSnapshot.time(r.releasedAt()),
        r.releaseReason(),restoreRequired);
  }
}
