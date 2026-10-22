package vn.fashion.notification;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyFactory;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import vn.fashion.platform.security.ServiceTokenIssuer;

/**
 * Fetches the reset token from user-service at send time (ADR-19 caller "notification-service", aud user-service).
 * The secret is returned to the caller only; it is never logged or stored. Empty = user answered 404 (used, superseded
 * or expired); any other failure is an unchecked exception that the sender treats as a definite error to retry.
 */
@Component
public class ResetPasswordSecrets {
  private final ServiceTokenIssuer issuer;
  private final ObjectMapper json;
  private final HttpClient http;
  private final String url;
  private final Duration timeout;

  public ResetPasswordSecrets(ObjectMapper json, @Value("${fashion.notification.user-url}") String url,
                              @Value("${fashion.notification.service-private-key:}") String privateKey,
                              @Value("${fashion.notification.service-public-key:}") String publicKey,
                              @Value("${fashion.notification.user-timeout:2s}") Duration timeout) {
    this.json = json;
    this.url = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    this.timeout = timeout;
    this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    ServiceTokenIssuer built = null;
    if (!privateKey.isBlank() && !publicKey.isBlank()) {
      try {
        var ec = KeyFactory.getInstance("EC");
        built = new ServiceTokenIssuer("notification-service",
            (ECPublicKey) ec.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKey.strip()))),
            (ECPrivateKey) ec.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKey.strip()))),
            Clock.systemUTC());
      } catch (Exception e) { throw new IllegalStateException("invalid notification service key pair", e); }
    }
    this.issuer = built;
  }

  public Optional<String> fetch(UUID challengeId) {
    if (issuer == null) throw new IllegalStateException("notification service key pair is not configured");
    try {
      var request = HttpRequest.newBuilder(URI.create(url + "/internal/api/v1/users/notification-secrets/" + challengeId)).timeout(timeout)
          .header("Authorization", "Bearer " + issuer.issue("user-service")).header("Accept", "application/json").GET().build();
      var response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 404) return Optional.empty();
      if (response.statusCode() != 200) throw new IllegalStateException("user-service answered " + response.statusCode());
      String secret = json.readTree(response.body()).path("data").path("secret").asString("");
      if (secret.isBlank()) throw new IllegalStateException("user-service sent no secret");
      return Optional.of(secret);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("user-service call interrupted");
    } catch (java.io.IOException e) {
      throw new IllegalStateException("user-service unreachable"); // never carry e: messages must not reach the log
    }
  }
}
