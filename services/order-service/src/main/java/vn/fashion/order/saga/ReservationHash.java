package vn.fashion.order.saga;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/** 03 section 4 reservation request_hash, the same formula inventory recomputes (ReservationSnapshot). */
public final class ReservationHash {
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
  private ReservationHash() { }

  /** expires_at as inventory stores it: UTC, milliseconds. */
  public static String time(Instant t) { return TIME.format(t); }

  /** SKUs trimmed, uppercased, merged and sorted. */
  public static TreeMap<String, Integer> canonical(Map<String, Integer> skus) {
    var merged = new TreeMap<String, Integer>();
    skus.forEach((sku, quantity) -> merged.merge(sku.strip().toUpperCase(Locale.ROOT), quantity, Integer::sum));
    return merged;
  }

  public static String of(UUID orderId, String orderNo, Instant expiresAt, Map<String, Integer> skus) {
    String escaped = orderNo.replace("\\", "\\\\").replace("|", "\\|");
    String text = orderId + "|" + escaped + "|" + time(expiresAt) + "|"
        + canonical(skus).entrySet().stream().map(e -> e.getKey() + ":" + e.getValue()).collect(Collectors.joining(","));
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
    catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
  }
}
