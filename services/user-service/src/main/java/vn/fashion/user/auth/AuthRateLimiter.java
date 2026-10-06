package vn.fashion.user.auth;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import vn.fashion.user.web.Api;

/** Fixed windows starting on the first attempt; INCR and expiry are one atomic Redis operation. */
@Component
public class AuthRateLimiter {
  private static final RedisScript<Long> LIMIT = new DefaultRedisScript<>("""
      local count = tonumber(redis.call('GET', KEYS[1]) or '0')
      local ttl = redis.call('PTTL', KEYS[1])
      if count >= tonumber(ARGV[1]) then
        if ttl < 0 then
          redis.call('PEXPIRE', KEYS[1], ARGV[2])
          ttl = tonumber(ARGV[2])
        end
        return math.max(1, ttl)
      end
      redis.call('INCR', KEYS[1])
      if ttl < 0 then redis.call('PEXPIRE', KEYS[1], ARGV[2]) end
      return 0
      """, Long.class);
  private final StringRedisTemplate redis;
  private final int loginLimit, forgotIpLimit, forgotEmailLimit;
  private final long loginWindow, forgotWindow;
  private final String prefix;
  private final SecretKeySpec bucketKey;

  public AuthRateLimiter(StringRedisTemplate redis,
      @Value("${fashion.user.rate-limit.login-limit:20}") int loginLimit,
      @Value("${fashion.user.rate-limit.login-window-seconds:300}") long loginWindow,
      @Value("${fashion.user.rate-limit.forgot-ip-limit:20}") int forgotIpLimit,
      @Value("${fashion.user.rate-limit.forgot-email-limit:5}") int forgotEmailLimit,
      @Value("${fashion.user.rate-limit.forgot-window-seconds:3600}") long forgotWindow,
      @Value("${fashion.environment:local}") String environment,
      @Value("${fashion.user.secret-key}") String secretKey) {
    if (loginLimit < 1 || forgotIpLimit < 1 || forgotEmailLimit < 1 || loginWindow < 1 || forgotWindow < 1) {
      throw new IllegalArgumentException("Auth rate limits and windows must be positive");
    }
    if (!environment.matches("[a-z0-9][a-z0-9_-]{0,31}")) {
      throw new IllegalArgumentException("FASHION_ENV must be 1..32 lowercase letters, digits, hyphens or underscores");
    }
    byte[] master = Base64.getDecoder().decode(secretKey.strip());
    if (master.length != 32) {
      throw new IllegalArgumentException("USER_SECRET_KEY must be 32 bytes, Base64 encoded");
    }
    prefix = environment + ":user:rate:";
    bucketKey = new SecretKeySpec(hmac(new SecretKeySpec(master, "HmacSHA256"),
        "fashion:user:rate-limit:v1:" + environment), "HmacSHA256");
    this.redis = redis;
    this.loginLimit = loginLimit;
    this.forgotIpLimit = forgotIpLimit;
    this.forgotEmailLimit = forgotEmailLimit;
    this.loginWindow = Math.multiplyExact(loginWindow, 1000);
    this.forgotWindow = Math.multiplyExact(forgotWindow, 1000);
  }

  public void login(String ip) {
    check("login:ip", ip, loginLimit, loginWindow);
  }

  public void forgotIp(String ip) {
    check("forgot:ip", ip, forgotIpLimit, forgotWindow);
  }

  public void forgotEmail(String normalizedEmail) {
    check("forgot:email", normalizedEmail, forgotEmailLimit, forgotWindow);
  }

  private void check(String scope, String value, int limit, long windowMillis) {
    Long wait;
    try {
      String identity = HexFormat.of().formatHex(hmac(bucketKey, scope + ":" + value));
      wait = redis.execute(LIMIT, List.of(prefix + scope + ":" + identity),
          Integer.toString(limit), Long.toString(windowMillis));
    } catch (DataAccessException e) {
      throw new Api.Problem(HttpStatus.SERVICE_UNAVAILABLE, "TEMPORARILY_UNAVAILABLE",
          "DEPENDENCY_UNAVAILABLE", List.of());
    }
    if (wait == null) {
      throw new Api.Problem(HttpStatus.SERVICE_UNAVAILABLE, "TEMPORARILY_UNAVAILABLE",
          "DEPENDENCY_UNAVAILABLE", List.of());
    }
    if (wait > 0) {
      throw new Limited((wait - 1) / 1000 + 1);
    }
  }

  private static byte[] hmac(SecretKeySpec key, String value) {
    try {
      // Mac is mutable: each concurrent request gets its own instance.
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(key);
      return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("auth quota crypto failed", e);
    }
  }

  public static final class Limited extends RuntimeException {
    private final long retryAfter;

    Limited(long retryAfter) {
      super("RETRY_LATER");
      this.retryAfter = retryAfter;
    }

    public long retryAfter() {
      return retryAfter;
    }
  }
}
