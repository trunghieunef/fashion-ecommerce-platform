package vn.fashion.payment.vnpay;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * TASK:PAY-02 spec section 6: public VNPay endpoints (Gateway route payment-vnpay-public). The IPN is always HTTP 200 with
 * VNPay's {RspCode, Message}; 00 only after the commit. The return only redirects: the browser proves nothing.
 */
@RestController
@RequestMapping("/api/v1/payments/vnpay")
public class VnPayController {
  private static final Logger LOG = LoggerFactory.getLogger(VnPayController.class);
  private static final List<String> REQUIRED = List.of("vnp_TmnCode", "vnp_Amount", "vnp_TxnRef", "vnp_ResponseCode",
      "vnp_TransactionStatus", "vnp_TransactionNo", "vnp_PayDate", "vnp_SecureHash");
  private final VnPayConfig config;
  private final VnPayResults results;

  public VnPayController(VnPayConfig config, VnPayResults results) { this.config = config; this.results = results; }

  @GetMapping("/ipn")
  public ResponseEntity<Map<String, String>> ipn(@RequestParam Map<String, String> params) {
    try {
      if (!config.enabled() || REQUIRED.stream().anyMatch(k -> params.get(k) == null || params.get(k).isEmpty())) return ack("99", "Unknow error");
      if (!VnPaySigner.valid(config.hashSecret(), params) || !results.ours(params.get("vnp_TmnCode"))) {
        LOG.warn("VNPay IPN rejected: invalid signature or merchant"); // never the parameters
        return ack("97", "Invalid signature");
      }
      String amount = params.get("vnp_Amount");
      if (!amount.matches("[0-9]{1,18}")) return ack("99", "Unknow error");
      var e = new VnPayResults.Evidence(params.get("vnp_TxnRef"), Long.parseLong(amount), params.get("vnp_ResponseCode"),
          params.get("vnp_TransactionStatus"), params.get("vnp_TransactionNo"), params.get("vnp_PayDate"), params.get("vnp_BankCode"),
          params.get("vnp_CardType"), params.get("vnp_TransactionNo") + ":" + params.get("vnp_ResponseCode") + ":"
          + params.get("vnp_TransactionStatus"));
      return switch (results.apply("WEBHOOK", e)) {
        case APPLIED -> ack("00", "Confirm Success");
        case ALREADY_CONFIRMED -> ack("02", "Order already confirmed");
        case NOT_FOUND -> ack("01", "Order not found");
        case AMOUNT_MISMATCH -> ack("04", "invalid amount");
      };
    } catch (RuntimeException ex) {
      // VNPay retries a 99 (up to 10 times, 5 minutes apart); the exception type only, the message may echo input.
      LOG.warn("VNPay IPN failed: {}", ex.getClass().getName());
      return ack("99", "Unknow error");
    }
  }

  @GetMapping("/return")
  public ResponseEntity<Void> back(@RequestParam(name = "vnp_TxnRef", required = false) String txnRef) {
    String base = config.storefrontUrl().toString().replaceAll("/+$", "");
    String target = txnRef != null && txnRef.matches("[A-Za-z0-9-]{1,32}") ? base + "/orders/" + txnRef : base + "/";
    return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(target)).build();
  }

  private static ResponseEntity<Map<String, String>> ack(String code, String message) {
    return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).header("Cache-Control", "no-store")
        .body(Map.of("RspCode", code, "Message", message));
  }
}
