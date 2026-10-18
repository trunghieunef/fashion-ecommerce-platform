package vn.fashion.inventory.reservation;

import static org.assertj.core.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** TASK:PLT-05 review I1: a sweep run by the real scheduler is not a traced request, so its outbox rows carry no trace. */
class ScheduledSweepTraceIntegrationTest extends ReservationTestSupport {
  @DynamicPropertySource static void sweep(DynamicPropertyRegistry r) {
    r.add("fashion.inventory.reservation-sweep.enabled", () -> "true");
    r.add("fashion.inventory.reservation-sweep.interval", () -> "200ms");
  }

  @Test void scheduledSweepWritesUntracedOutbox() throws Exception {
    seedStock("SKU-S", 5, 0);
    var s = snapshot(UUID.randomUUID(), in(15), "SKU-S", 1); data(reserve(s), 201);
    jdbc.update("update stock_reservations set expires_at=now()-interval '1 second' where order_id=?", s.orderId());
    long deadline = System.nanoTime() + 10_000_000_000L;
    while (outbox("INVENTORY_RELEASED") == 0 && System.nanoTime() < deadline) Thread.sleep(100);
    assertThat(outbox("INVENTORY_RELEASED")).isEqualTo(1);
    assertThat(jdbc.queryForObject("select trace_parent from outbox_events where event_type='INVENTORY_RELEASED'", String.class)).isNull();
  }
}
