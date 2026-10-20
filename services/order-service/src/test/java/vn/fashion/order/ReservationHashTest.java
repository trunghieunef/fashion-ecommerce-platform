package vn.fashion.order;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.fashion.order.saga.ReservationHash;

/** 03 section 4: the same request_hash inventory computes (ReservationSnapshot), fixed vector computed with Python. */
class ReservationHashTest {
  @Test void matchesTheInventoryFormula() {
    var skus = new LinkedHashMap<String, Integer>();
    skus.put(" tee-m ", 1);
    skus.put("TEE-M", 2);
    skus.put("AB-1", 3);
    // order_no ORD\X|Y escapes to ORD\\X\|Y; expires_at keeps milliseconds only; SKUs trimmed, uppercased, merged, sorted.
    assertThat(ReservationHash.of(UUID.fromString("11111111-1111-4111-8111-111111111111"), "ORD\\X|Y",
        Instant.parse("2026-10-21T03:00:30.123456Z"), skus))
        .isEqualTo("84d31f83caa047c81aed91d63a3fc0c507e4b06fa98a1c23c143e554dbaf148e");
    assertThat(ReservationHash.time(Instant.parse("2026-10-21T03:00:30.123456Z"))).isEqualTo("2026-10-21T03:00:30.123Z");
  }
}
