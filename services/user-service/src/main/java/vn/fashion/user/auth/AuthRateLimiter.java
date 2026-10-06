package vn.fashion.user.auth;

import java.util.List;
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

  public AuthRateLimiter(StringRedisTemplate redis,
      @Value("${fashion.user.rate-limit.login-limit:20}") int loginLimit,
      @Value("${fashion.user.rate-limit.login-window-seconds:300}") long loginWindow,
      @Value("${fashion.user.rate-limit.forgot-ip-limit:20}") int forgotIpLimit,
      @Value("${fashion.user.rate-limit.forgot-email-limit:5}") int forgotEmailLimit,
      @Value("${fashion.user.rate-limit.forgot-window-seconds:3600}") long forgotWindow) {
    if (loginLimit < 1 || forgotIpLimit < 1 || forgotEmailLimit < 1 || loginWindow < 1 || forgotWindow < 1) {
      throw new IllegalArgumentException("Auth rate limits and windows must be positive");
    }
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
      wait = redis.execute(LIMIT, List.of("user:rate:" + scope + ":" + AuthService.sha256(value)),
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
