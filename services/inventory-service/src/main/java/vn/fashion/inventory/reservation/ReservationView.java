package vn.fashion.inventory.reservation;

import java.util.List;
import java.util.UUID;

public record ReservationView(UUID reservationId,UUID orderId,String orderNo,String status,String expiresAt,
    List<ReservationSnapshot.Item> items,long version,String committedAt,String releasedAt,String releaseReason,
    boolean restoreRequired) {
  static ReservationView of(ReservationRow r,List<ReservationSnapshot.Item> items,boolean restoreRequired) {
    return new ReservationView(r.id(),r.orderId(),r.orderNo(),r.status(),ReservationSnapshot.time(r.expiresAt()),items,r.version(),
        r.committedAt()==null?null:r.committedAt().toString(),r.releasedAt()==null?null:r.releasedAt().toString(),
        r.releaseReason(),restoreRequired);
  }
}
