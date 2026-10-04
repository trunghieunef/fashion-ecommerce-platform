package vn.fashion.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class ServiceTokenTest {
  private static final Instant NOW = Instant.parse("2026-10-04T10:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final KeyPair ORDER = ecKeyPair();
  private static final KeyPair CART = ecKeyPair();

  private final ServiceTokenVerifier inventory =
      new ServiceTokenVerifier("inventory", Map.of("order", (ECPublicKey) ORDER.getPublic()), CLOCK);

  @Test
  void allowlistedCallerWithTokenForThisServiceIsAccepted() {
    assertThat(inventory.verify(issuer("order", ORDER, CLOCK).issue("inventory"))).contains("order");
  }

  @Test
  void tokenForAnotherAudienceIsRejected() {
    assertThat(inventory.verify(issuer("order", ORDER, CLOCK).issue("payment"))).isEmpty();
  }

  @Test
  void callerOutsideTheAllowlistIsRejected() {
    assertThat(inventory.verify(issuer("cart", CART, CLOCK).issue("inventory"))).isEmpty();
  }

  @Test
  void tokenClaimingAnAllowlistedCallerButSignedWithAnotherKeyIsRejected() {
    assertThat(inventory.verify(issuer("order", CART, CLOCK).issue("inventory"))).isEmpty();
  }

  @Test
  void expiredTokenIsRejected() {
    var issuedEarlier = Clock.fixed(NOW.minus(ServiceTokenIssuer.TTL).minusSeconds(10), ZoneOffset.UTC);
    assertThat(inventory.verify(issuer("order", ORDER, issuedEarlier).issue("inventory"))).isEmpty();
  }

  @Test
  void tokenStillValidJustBeforeExpiryIsAccepted() {
    var issuedEarlier = Clock.fixed(NOW.minus(ServiceTokenIssuer.TTL).plusSeconds(1), ZoneOffset.UTC);
    assertThat(inventory.verify(issuer("order", ORDER, issuedEarlier).issue("inventory"))).contains("order");
  }

  @Test
  void validlySignedLongLivedTokenIsRejected() throws Exception {
    var claims = new JWTClaimsSet.Builder(claims("order", "inventory"))
        .expirationTime(Date.from(NOW.plus(Duration.ofHours(1)))).build();
    var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID("order").build(), claims);
    jwt.sign(new ECDSASigner((ECPrivateKey) ORDER.getPrivate()));
    assertThat(inventory.verify(jwt.serialize())).isEmpty();
  }

  @Test
  void tokenWhoseSubjectDiffersFromIssuerIsRejected() throws Exception {
    var claims = new JWTClaimsSet.Builder(claims("order", "inventory")).subject("payment").build();
    var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID("order").build(), claims);
    jwt.sign(new ECDSASigner((ECPrivateKey) ORDER.getPrivate()));
    assertThat(inventory.verify(jwt.serialize())).isEmpty();
  }

  @Test
  void unsignedAlgNoneTokenIsRejected() {
    String token = new PlainJWT(claims("order", "inventory")).serialize();
    assertThat(inventory.verify(token)).isEmpty();
  }

  @Test
  void hmacTokenIsRejected() throws Exception {
    var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims("order", "inventory"));
    jwt.sign(new MACSigner(new byte[32]));
    assertThat(inventory.verify(jwt.serialize())).isEmpty();
  }

  @Test
  void malformedOrMissingTokenIsRejected() {
    assertThat(inventory.verify("not-a-jwt")).isEmpty();
    assertThat(inventory.verify("")).isEmpty();
    assertThat(inventory.verify(null)).isEmpty();
  }

  @Test
  void rejectionNeverLogsTheToken(CapturedOutput output) {
    String token = issuer("cart", CART, CLOCK).issue("inventory");
    inventory.verify(token);
    assertThat(output.getAll()).doesNotContain(token).doesNotContain(token.split("\\.")[2]);
  }

  private static ServiceTokenIssuer issuer(String name, KeyPair keys, Clock clock) {
    return new ServiceTokenIssuer(name, (ECPublicKey) keys.getPublic(), (ECPrivateKey) keys.getPrivate(), clock);
  }

  private static JWTClaimsSet claims(String caller, String audience) {
    return new JWTClaimsSet.Builder().issuer(caller).subject(caller).audience(List.of(audience))
        .issueTime(Date.from(NOW)).expirationTime(Date.from(NOW.plus(Duration.ofSeconds(60)))).build();
  }

  private static KeyPair ecKeyPair() {
    try {
      var generator = KeyPairGenerator.getInstance("EC");
      generator.initialize(new ECGenParameterSpec("secp256r1"));
      return generator.generateKeyPair();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
