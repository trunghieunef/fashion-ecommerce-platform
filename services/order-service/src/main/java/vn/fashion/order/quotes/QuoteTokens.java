package vn.fashion.order.quotes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.fashion.order.web.Api;

/**
 * quote_token = base64url(JSON {kid, quote}) "." base64url(HMAC-SHA256). The MAC is checked before the payload is parsed;
 * the kid names the key so a later rotation can accept the previous one.
 */
@Component
public class QuoteTokens {
  private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
  private final ObjectMapper json;
  private final String kid;
  private final byte[] secret;

  public QuoteTokens(ObjectMapper json, @Value("${fashion.order.quote-kid:q1}") String kid,
                     @Value("${fashion.order.quote-secret:}") String secret) {
    this.json = json; this.kid = kid;
    this.secret = secret.isBlank() ? null : Base64.getDecoder().decode(secret.strip());
    if (this.secret != null && this.secret.length < 32) throw new IllegalStateException("fashion.order.quote-secret must be >= 32 bytes");
  }

  public String sign(Quote quote) {
    String payload = B64.encodeToString(json.writeValueAsBytes(Map.of("kid", kid, "quote", quote)));
    return payload + "." + B64.encodeToString(mac(payload));
  }

  /** The quote of a token this service signed, else 400 INVALID_QUOTE (expiry is checked by the caller). */
  public Quote verify(String token) {
    try {
      int dot = token.indexOf('.');
      String payload = token.substring(0, dot);
      if (!MessageDigest.isEqual(mac(payload), Base64.getUrlDecoder().decode(token.substring(dot + 1)))) throw invalid();
      JsonNode node = json.readTree(Base64.getUrlDecoder().decode(payload));
      if (!kid.equals(node.path("kid").asString(null))) throw invalid();
      return json.treeToValue(node.path("quote"), Quote.class);
    } catch (Api.Problem e) { throw e; }
    catch (RuntimeException e) { throw invalid(); }
  }

  private byte[] mac(String payload) {
    if (secret == null) throw new IllegalStateException("fashion.order.quote-secret is not configured");
    try {
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret, "HmacSHA256"));
      return mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII));
    } catch (java.security.GeneralSecurityException e) { throw new IllegalStateException(e); }
  }

  public static Api.Problem invalid() {
    return new Api.Problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "INVALID_QUOTE", List.of());
  }
}
