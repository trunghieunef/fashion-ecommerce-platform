package vn.fashion.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.EncryptionMethod;
import com.nimbusds.jose.JWEAlgorithm;
import com.nimbusds.jose.JWEHeader;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.DirectEncrypter;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.EncryptedJWT;
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
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** ADR-21: member access tokens signed by user-service, verified with configured public keys. */
class AccessTokenVerifierTest {
  private static final Instant NOW = Instant.parse("2026-10-05T10:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final KeyPair CURRENT = ecKeyPair();
  private static final KeyPair NEXT = ecKeyPair();
  private static final KeyPair OTHER = ecKeyPair();
  private static final UUID USER = UUID.fromString("88888888-8888-4888-8888-888888888888");

  private final AccessTokenVerifier verifier = new AccessTokenVerifier(
      Map.of("user-1", (ECPublicKey) CURRENT.getPublic(), "user-2", (ECPublicKey) NEXT.getPublic()), CLOCK);

  @Test
  void validTokenYieldsTheActor() throws Exception {
    assertThat(verifier.verify(sign(CURRENT, "user-1", claims().build())))
        .contains(new AccessTokenVerifier.Actor(USER, 3));
  }

  @Test
  void secondConfiguredKeyIsAcceptedForRotation() throws Exception {
    assertThat(verifier.verify(sign(NEXT, "user-2", claims().build()))).isPresent();
  }

  @Test
  void unknownKidOrWrongKeyIsRejected() throws Exception {
    assertThat(verifier.verify(sign(CURRENT, "user-9", claims().build()))).isEmpty();
    assertThat(verifier.verify(sign(OTHER, "user-1", claims().build()))).isEmpty();
  }

  @Test
  void wrongIssuerOrAudienceIsRejected() throws Exception {
    assertThat(verifier.verify(sign(CURRENT, "user-1", claims().issuer("evil").build()))).isEmpty();
    assertThat(verifier.verify(sign(CURRENT, "user-1", claims().audience("other-api").build()))).isEmpty();
  }

  @Test
  void expiredFutureOrLongLivedTokensAreRejected() throws Exception {
    var expired = claims().issueTime(Date.from(NOW.minusSeconds(1000))).expirationTime(Date.from(NOW.minusSeconds(100)));
    var future = claims().issueTime(Date.from(NOW.plusSeconds(60))).expirationTime(Date.from(NOW.plusSeconds(960)));
    var longLived = claims().expirationTime(Date.from(NOW.plus(Duration.ofHours(2))));

    assertThat(verifier.verify(sign(CURRENT, "user-1", expired.build()))).isEmpty();
    assertThat(verifier.verify(sign(CURRENT, "user-1", future.build()))).isEmpty();
    assertThat(verifier.verify(sign(CURRENT, "user-1", longLived.build()))).isEmpty();
  }

  @Test
  void subjectMustBeAUuidAndAuthVersionPresent() throws Exception {
    assertThat(verifier.verify(sign(CURRENT, "user-1", claims().subject("admin").build()))).isEmpty();
    var noVersion = new JWTClaimsSet.Builder(claims().build()).claim("auth_version", null).build();
    assertThat(verifier.verify(sign(CURRENT, "user-1", noVersion))).isEmpty();
  }

  @Test
  void unsignedHmacEncryptedAndGarbageTokensAreRejectedWithoutException() throws Exception {
    var hmac = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID("user-1").build(), claims().build());
    hmac.sign(new MACSigner(new byte[32]));
    var jwe = new EncryptedJWT(new JWEHeader(JWEAlgorithm.DIR, EncryptionMethod.A128GCM), claims().build());
    jwe.encrypt(new DirectEncrypter(new byte[16]));

    assertThat(verifier.verify(new PlainJWT(claims().build()).serialize())).isEmpty();
    assertThat(verifier.verify(hmac.serialize())).isEmpty();
    assertThat(verifier.verify(jwe.serialize())).isEmpty();
    assertThat(verifier.verify("not-a-jwt")).isEmpty();
    assertThat(verifier.verify(null)).isEmpty();
  }

  @Test
  void parsesConfiguredKeysFromKidColonBase64List() {
    String config = "user-1:" + Base64.getEncoder().encodeToString(CURRENT.getPublic().getEncoded())
        + ", user-2:" + Base64.getEncoder().encodeToString(NEXT.getPublic().getEncoded());

    var keys = AccessTokenVerifier.parseKeys(config);

    assertThat(keys).containsOnlyKeys("user-1", "user-2");
    assertThat(keys.get("user-1").getW()).isEqualTo(((ECPublicKey) CURRENT.getPublic()).getW());
  }

  private static JWTClaimsSet.Builder claims() {
    return new JWTClaimsSet.Builder()
        .issuer("user-service").audience("fashion-api").subject(USER.toString())
        .issueTime(Date.from(NOW)).expirationTime(Date.from(NOW.plusSeconds(900)))
        .claim("auth_version", 3L);
  }

  private static String sign(KeyPair keys, String kid, JWTClaimsSet claims) throws Exception {
    var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(kid).build(), claims);
    jwt.sign(new ECDSASigner((ECPrivateKey) keys.getPrivate()));
    return jwt.serialize();
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
