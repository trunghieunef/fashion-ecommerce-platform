package vn.fashion.cart;

import static org.assertj.core.api.Assertions.assertThat;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class CartIdentityIntegrationTest extends CartTestSupport {
  static final String CARTS = "/api/v1/carts";

  static String setCookie(HttpResponse<String> r) { return r.headers().firstValue("Set-Cookie").orElse(null); }
  static String token(HttpResponse<String> r) { return setCookie(r).split(";", 2)[0].substring("cart_token=".length()); }
  static String sha256(String s) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
  }

  @Test void guestPostCreatesCartAndCookie() throws Exception {
    var r = send("POST", CARTS, null);
    var cart = data(r, 201);
    assertThat(cart.path("owner").asString()).isEqualTo("GUEST");
    assertThat(cart.path("version").asLong()).isZero();
    String cookie = setCookie(r);
    assertThat(cookie).startsWith("cart_token=").contains("HttpOnly").contains("Secure").contains("SameSite=Lax")
        .contains("Path=/api/v1/carts").contains("Max-Age=2592000");
    String token = token(r);
    assertThat(token).matches("[A-Za-z0-9_-]{43}");
    var row = jdbc.queryForMap("select guest_token_hash, user_id from carts where id=?", UUID.fromString(cart.path("cart_id").asString()));
    assertThat(row.get("guest_token_hash")).isEqualTo(sha256(token));
    assertThat(row.get("user_id")).isNull();
    assertThat(r.body()).doesNotContain(token);
  }

  @Test void guestReturnsWithCookieGetsSameCart() {
    var first = send("POST", CARTS, null);
    String id = data(first, 201).path("cart_id").asString();
    var again = send("POST", CARTS, null, "Cookie", "cart_token=" + token(first));
    assertThat(data(again, 200).path("cart_id").asString()).isEqualTo(id);
    assertThat(setCookie(again)).isNull();
  }

  @Test void malformedCookieGetsNewCart() {
    for (String bad : new String[] {"a", "x".repeat(5000), "%%%"}) {
      var r = send("POST", CARTS, null, "Cookie", "cart_token=" + bad);
      assertThat(r.statusCode()).as(bad.substring(0, Math.min(5, bad.length()))).isEqualTo(201);
      assertThat(setCookie(r)).startsWith("cart_token=");
    }
  }

  @Test void expiredGuestCartIsGone() {
    var r = send("POST", CARTS, null);
    String id = data(r, 201).path("cart_id").asString(), cookie = "cart_token=" + token(r);
    jdbc.update("update carts set expires_at=now()+interval '1 day' where id=?", UUID.fromString(id));
    data(send("GET", CARTS + "/" + id, null, "Cookie", cookie), 200);
    var expires = jdbc.queryForObject("select expires_at from carts where id=?", java.sql.Timestamp.class, UUID.fromString(id)).toInstant();
    assertThat(expires).isAfter(Instant.now().plus(29, ChronoUnit.DAYS));
    jdbc.update("update carts set expires_at=now()-interval '1 second' where id=?", UUID.fromString(id));
    assertThat(problem(send("GET", CARTS + "/" + id, null, "Cookie", cookie), 404)).isEqualTo("CART_NOT_FOUND");
    var fresh = send("POST", CARTS, null, "Cookie", cookie);
    assertThat(data(fresh, 201).path("cart_id").asString()).isNotEqualTo(id);
  }

  @Test void memberPostCreatesOneActiveCart() throws Exception {
    String bearer = "Bearer " + memberToken();
    String id = data(send("POST", CARTS, null, "Authorization", bearer), 201).path("cart_id").asString();
    var again = send("POST", CARTS, null, "Authorization", bearer);
    assertThat(data(again, 200).path("cart_id").asString()).isEqualTo(id);
    assertThat(data(again, 200).path("owner").asString()).isEqualTo("MEMBER");
    assertThat(data(send("GET", CARTS + "/mine", null, "Authorization", bearer), 200).path("cart_id").asString()).isEqualTo(id);

    UUID other = UUID.randomUUID(); String otherBearer = "Bearer " + memberToken(other);
    var codes = new ConcurrentLinkedQueue<Integer>();
    try (var pool = Executors.newFixedThreadPool(5)) {
      for (int i = 0; i < 5; i++) pool.submit(() -> codes.add(send("POST", CARTS, null, "Authorization", otherBearer).statusCode()));
      pool.shutdown(); assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
    }
    assertThat(codes).containsOnly(200, 201);
    assertThat(jdbc.queryForObject("select count(*) from carts where user_id=?", Integer.class, other)).isEqualTo(1);
  }

  @Test void invalidBearerIs401WithoutCookieFallback() {
    String cookie = "cart_token=" + token(send("POST", CARTS, null));
    var r = send("POST", CARTS, null, "Authorization", "Bearer not-a-jwt", "Cookie", cookie);
    assertThat(problem(r, 401)).isEqualTo("INVALID_ACCESS_TOKEN");
  }

  @Test void mineRequiresMember() {
    assertThat(problem(send("GET", CARTS + "/mine", null), 401)).isEqualTo("INVALID_ACCESS_TOKEN");
    assertThat(problem(send("GET", CARTS + "/mine", null, "Authorization", "Bearer " + memberToken(UUID.randomUUID())), 404))
        .isEqualTo("CART_NOT_FOUND");
  }
}
