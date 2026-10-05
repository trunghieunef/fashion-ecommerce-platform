package vn.fashion.user.auth;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Short-lived secrets handed to notification (06 section 2.1): AES-256-GCM encrypted in Redis under
 * the challenge id, expiring with the secret. The challenge id and purpose are bound as associated
 * data, so a value cannot be replayed under another challenge. Redis never sees the plaintext.
 */
@Component
public class SecretVault {
  public record Secret(UUID challengeId, String purpose, String value, Instant expiresAt) {
  }

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final String PREFIX = "user:notification-secret:";

  private final StringRedisTemplate redis;
  private final ObjectMapper json;
  private final SecretKeySpec key;

  public SecretVault(StringRedisTemplate redis, ObjectMapper json, @Value("${fashion.user.secret-key}") String key) {
    byte[] bytes = Base64.getDecoder().decode(key.strip());
    if (bytes.length != 32) {
      throw new IllegalStateException("USER_SECRET_KEY must be 32 bytes, Base64 encoded");
    }
    this.redis = redis;
    this.json = json;
    this.key = new SecretKeySpec(bytes, "AES");
  }

  public void put(Secret secret) {
    byte[] iv = new byte[12];
    RANDOM.nextBytes(iv);
    byte[] sealed = crypt(Cipher.ENCRYPT_MODE, iv, secret, secret.value().getBytes(StandardCharsets.UTF_8));
    byte[] stored = new byte[iv.length + sealed.length];
    System.arraycopy(iv, 0, stored, 0, iv.length);
    System.arraycopy(sealed, 0, stored, iv.length, sealed.length);
    String value = json.writeValueAsString(Map.of("purpose", secret.purpose(),
        "expires_at", secret.expiresAt().toString(), "ciphertext", Base64.getEncoder().encodeToString(stored)));
    redis.opsForValue().set(PREFIX + secret.challengeId(), value,
        Duration.between(Instant.now(), secret.expiresAt()).plusSeconds(1));
  }

  public Optional<Secret> get(UUID challengeId) {
    String value = redis.opsForValue().get(PREFIX + challengeId);
    if (value == null) {
      return Optional.empty();
    }
    var node = json.readTree(value);
    var meta = new Secret(challengeId, node.path("purpose").asText(),
        null, Instant.parse(node.path("expires_at").asText()));
    if (!meta.expiresAt().isAfter(Instant.now())) {
      return Optional.empty();
    }
    byte[] stored = Base64.getDecoder().decode(node.path("ciphertext").asText());
    byte[] iv = java.util.Arrays.copyOf(stored, 12);
    byte[] sealed = java.util.Arrays.copyOfRange(stored, 12, stored.length);
    String plain = new String(crypt(Cipher.DECRYPT_MODE, iv, meta, sealed), StandardCharsets.UTF_8);
    return Optional.of(new Secret(challengeId, meta.purpose(), plain, meta.expiresAt()));
  }

  /** Throws a DataAccessException when Redis cannot answer (connection failure or timeout). */
  public void requireAvailable() {
    redis.execute((RedisCallback<String>) connection -> connection.ping());
  }

  public void delete(UUID challengeId) {
    redis.delete(PREFIX + challengeId);
  }

  private byte[] crypt(int mode, byte[] iv, Secret secret, byte[] input) {
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(mode, key, new GCMParameterSpec(128, iv));
      cipher.updateAAD((secret.challengeId() + ":" + secret.purpose()).getBytes(StandardCharsets.UTF_8));
      return cipher.doFinal(input);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("secret handoff crypto failed", e);
    }
  }
}
