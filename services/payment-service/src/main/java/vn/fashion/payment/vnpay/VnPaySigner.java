package vn.fashion.payment.vnpay;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.JsonNode;

/** TASK:PAY-02 spec sections 5 and 8: VNPay 2.1.0 HMAC-SHA512 signing, GMT+7 dates, QueryDR checksum strings. */
public final class VnPaySigner {
  private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
  private static final String[] QUERY_REQUEST = {"vnp_RequestId", "vnp_Version", "vnp_Command", "vnp_TmnCode", "vnp_TxnRef",
      "vnp_TransactionDate", "vnp_CreateDate", "vnp_IpAddr", "vnp_OrderInfo"};
  private static final String[] QUERY_RESPONSE = {"vnp_ResponseId", "vnp_Command", "vnp_ResponseCode", "vnp_Message",
      "vnp_TmnCode", "vnp_TxnRef", "vnp_Amount", "vnp_BankCode", "vnp_PayDate", "vnp_TransactionNo", "vnp_TransactionType",
      "vnp_TransactionStatus", "vnp_OrderInfo", "vnp_PromotionCode", "vnp_PromotionAmount"};

  private VnPaySigner() { }

  public static String canonical(Map<String, String> params) {
    return new TreeMap<>(params).entrySet().stream()
        .filter(e -> e.getKey().startsWith("vnp_") && !e.getKey().equals("vnp_SecureHash") && !e.getKey().equals("vnp_SecureHashType")
            && e.getValue() != null && !e.getValue().isEmpty())
        .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.US_ASCII))
        .collect(Collectors.joining("&"));
  }

  public static String hmac(String secret, String data) {
    try {
      Mac mac = Mac.getInstance("HmacSHA512");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
      return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.GeneralSecurityException e) { throw new IllegalStateException(e); }
  }

  public static boolean valid(String secret, Map<String, String> params) {
    String given = params.get("vnp_SecureHash");
    return given != null && MessageDigest.isEqual(hmac(secret, canonical(params)).getBytes(StandardCharsets.US_ASCII),
        given.toLowerCase().getBytes(StandardCharsets.US_ASCII));
  }

  public static String payUrl(VnPayConfig c, Map<String, String> params) {
    String q = canonical(params);
    return c.payUrl() + "?" + q + "&vnp_SecureHash=" + hmac(c.hashSecret(), q);
  }

  public static String format(Instant t) { return DATE.format(t.atZone(VN)); }

  public static Instant parse(String yyyyMMddHHmmss) { return LocalDateTime.parse(yyyyMMddHHmmss, DATE).atZone(VN).toInstant(); }

  public static String queryChecksumData(Map<String, String> req) {
    return String.join("|", java.util.Arrays.stream(QUERY_REQUEST).map(k -> req.getOrDefault(k, "")).toList());
  }

  public static String queryResponseChecksumData(JsonNode resp) {
    return String.join("|", java.util.Arrays.stream(QUERY_RESPONSE).map(k -> resp.path(k).asString("")).toList());
  }
}
