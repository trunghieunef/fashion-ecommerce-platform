package vn.fashion.cart.cart;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import vn.fashion.cart.web.Api;
import vn.fashion.platform.security.AccessTokenVerifier;

/** CART-01 spec section 3: Bearer member first (no fallback when invalid), else the guest cookie. */
@Component
public class CartIdentity {
  public static final String COOKIE = "cart_token";
  static final Duration GUEST_TTL = Duration.ofDays(30);
  private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
  private final AccessTokenVerifier verifier;
  private final SecureRandom random = new SecureRandom();

  public CartIdentity(@Value("${fashion.cart.jwt.public-keys}") String keys) {
    verifier = new AccessTokenVerifier(AccessTokenVerifier.parseKeys(keys), Clock.systemUTC());
  }

  public Optional<CartActor> resolve(HttpServletRequest request) {
    String authorization = request.getHeader("Authorization");
    if (authorization != null) {
      if (!authorization.regionMatches(true, 0, "Bearer ", 0, 7)) throw unauthorized();
      var actor = verifier.verify(authorization.substring(7).strip()).orElseThrow(CartIdentity::unauthorized);
      return Optional.of(CartActor.member(actor.userId()));
    }
    Cookie[] cookies = request.getCookies();
    if (cookies == null) return Optional.empty();
    for (Cookie cookie : cookies) {
      if (COOKIE.equals(cookie.getName()) && cookie.getValue() != null && TOKEN.matcher(cookie.getValue()).matches())
        return Optional.of(CartActor.guest(hash(cookie.getValue())));
    }
    return Optional.empty();
  }

  /** Raw cookie value of a guest request (no Authorization header), for re-issuing the sliding cookie. */
  public Optional<String> guestToken(HttpServletRequest request) {
    if (request.getHeader("Authorization") != null || request.getCookies() == null) return Optional.empty();
    for (Cookie cookie : request.getCookies()) {
      if (COOKIE.equals(cookie.getName()) && cookie.getValue() != null && TOKEN.matcher(cookie.getValue()).matches())
        return Optional.of(cookie.getValue());
    }
    return Optional.empty();
  }

  /** Spec section 3: 30 days since last activity also in the browser — re-issue after every successful guest call. */
  public void refresh(HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response) {
    guestToken(request).ifPresent(token -> response.addHeader(org.springframework.http.HttpHeaders.SET_COOKIE, cookie(token).toString()));
  }

  public String newGuestToken() {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  public static String hash(String token) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
    catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
  }

  public static ResponseCookie cookie(String token) {
    return ResponseCookie.from(COOKIE, token).httpOnly(true).secure(true).sameSite("Lax").path("/api/v1/carts")
        .maxAge(GUEST_TTL).build();
  }

  public static Api.Problem unauthorized() {
    return new Api.Problem(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "INVALID_ACCESS_TOKEN", List.of());
  }
}
