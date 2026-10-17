package vn.fashion.catalog.cache;

import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * CAT-02b spec section 2: cache-aside under one generation. The generation is read before the database,
 * so a slow reader can only write under a generation nobody reads any more. Redis never fails a request.
 */
@Component
public class PublicCache {
  private final StringRedisTemplate redis;
  private final ObjectMapper json;
  private final MeterRegistry meters;
  private final boolean enabled;
  private final String prefix;

  public PublicCache(StringRedisTemplate redis, ObjectMapper json, MeterRegistry meters,
                     @Value("${fashion.catalog.cache.enabled}") boolean enabled,
                     @Value("${fashion.catalog.cache.environment}") String environment) {
    this.redis = redis; this.json = json; this.meters = meters; this.enabled = enabled;
    this.prefix = environment + ":catalog:";
  }

  public <T> Optional<T> read(String route, Duration ttl, TypeReference<T> type, Supplier<Optional<T>> load, String... params) {
    if (!enabled) return load.get();
    String key;
    try {
      String gen = redis.opsForValue().get(prefix + "gen");
      key = prefix + "v1:g" + (gen == null ? "0" : gen) + ":" + route + ":" + digest(params);
      String cached = redis.opsForValue().get(key);
      if (cached != null) {
        try {
          T value = json.readValue(cached, type);
          count(route, "hit");
          return Optional.of(value);
        } catch (RuntimeException undecodable) { count(route, "error"); }
      }
    } catch (RuntimeException redisDown) {
      count(route, "error");
      return load.get();
    }
    count(route, "miss");
    var value = load.get();
    if (value.isPresent()) {
      try { redis.opsForValue().set(key, json.writeValueAsString(value.get()), ttl); }
      catch (RuntimeException redisDown) { count(route, "error"); }
    }
    return value;
  }

  /** Invalidates every public key at once; Redis errors propagate so the durable task is retried. */
  public long bumpGeneration() {
    Long gen = redis.opsForValue().increment(prefix + "gen");
    if (gen == null) throw new IllegalStateException("INCR returned no value");
    return gen;
  }

  private void count(String route, String outcome) {
    meters.counter("catalog.cache", "route", route, "outcome", outcome).increment();
  }

  /** SHA-256 of the validated parameters, each escaped (backslash, then pipe) and joined by pipe, as in 02a. */
  static String digest(String... params) {
    var joined = new StringBuilder();
    for (int i = 0; i < params.length; i++) {
      if (i > 0) joined.append('|');
      joined.append(params[i] == null ? "" : params[i].replace("\\", "\\\\").replace("|", "\\|"));
    }
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(joined.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
  }
}
