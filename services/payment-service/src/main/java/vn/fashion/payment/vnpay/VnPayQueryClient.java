package vn.fashion.payment.vnpay;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** TASK:PAY-02 spec section 8: one signed QueryDR call; the answer counts only with a valid checksum. */
@Component
public class VnPayQueryClient {
  public record Result(String responseId, String responseCode, JsonNode body) { }
  private static final Logger LOG = LoggerFactory.getLogger(VnPayQueryClient.class);
  private final VnPayConfig config;
  private final ObjectMapper json;
  private final RestClient http;

  public VnPayQueryClient(VnPayConfig config, ObjectMapper json) {
    this.config = config; this.json = json;
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(config.queryTimeout());
    factory.setReadTimeout(config.queryTimeout());
    this.http = RestClient.builder().requestFactory(factory).build();
  }

  /** null when the response checksum is wrong; network errors and timeouts throw (a query is a read: the caller retries later). */
  public Result query(String txnRef, String orderInfo, Instant transactionDate) {
    var req = new LinkedHashMap<String, String>();
    req.put("vnp_RequestId", UUID.randomUUID().toString().replace("-", ""));
    req.put("vnp_Version", "2.1.0");
    req.put("vnp_Command", "querydr");
    req.put("vnp_TmnCode", config.tmnCode());
    req.put("vnp_TxnRef", txnRef);
    req.put("vnp_OrderInfo", orderInfo);
    req.put("vnp_TransactionDate", VnPaySigner.format(transactionDate));
    req.put("vnp_CreateDate", VnPaySigner.format(Instant.now()));
    req.put("vnp_IpAddr", config.serverIp());
    req.put("vnp_SecureHash", VnPaySigner.hmac(config.hashSecret(), VnPaySigner.queryChecksumData(req)));
    String body = http.post().uri(config.apiUrl()).contentType(MediaType.APPLICATION_JSON).body(json.writeValueAsString(req))
        .retrieve().body(String.class);
    JsonNode resp = json.readTree(body);
    String expected = VnPaySigner.hmac(config.hashSecret(), VnPaySigner.queryResponseChecksumData(resp));
    String given = resp.path("vnp_SecureHash").asString("").toLowerCase();
    if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), given.getBytes(StandardCharsets.US_ASCII))) {
      LOG.warn("VNPay QueryDR response rejected: invalid checksum"); // never the body
      return null;
    }
    return new Result(resp.path("vnp_ResponseId").asString(""), resp.path("vnp_ResponseCode").asString(""), resp);
  }
}
