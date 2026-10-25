package vn.fashion.payment.vnpay;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** TASK:PAY-02 spec section 2. Blank tmnCode or hashSecret = VNPAY disabled. */
@ConfigurationProperties("fashion.payment.vnpay")
public record VnPayConfig(String tmnCode, String hashSecret, URI payUrl, URI apiUrl, URI returnUrl, URI storefrontUrl,
                          Duration linkTtl, String serverIp, Duration queryTimeout) {
  public VnPayConfig {
    if (payUrl == null) payUrl = URI.create("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html");
    if (apiUrl == null) apiUrl = URI.create("https://sandbox.vnpayment.vn/merchant_webapi/api/transaction");
    if (returnUrl == null) returnUrl = URI.create("http://localhost:8080/api/v1/payments/vnpay/return");
    if (storefrontUrl == null) storefrontUrl = URI.create("http://localhost:5173");
    if (linkTtl == null) linkTtl = Duration.ofMinutes(15);
    if (serverIp == null || serverIp.isBlank()) serverIp = "127.0.0.1";
    if (queryTimeout == null) queryTimeout = Duration.ofSeconds(10);
  }

  public boolean enabled() {
    return tmnCode != null && !tmnCode.isBlank() && hashSecret != null && !hashSecret.isBlank();
  }

  @Override public String toString() {
    return "VnPayConfig[tmnCode=" + tmnCode + ", payUrl=" + payUrl + ", apiUrl=" + apiUrl + ", returnUrl=" + returnUrl
        + ", storefrontUrl=" + storefrontUrl + ", linkTtl=" + linkTtl + ", serverIp=" + serverIp
        + ", queryTimeout=" + queryTimeout + ", enabled=" + enabled() + "]";
  }
}
