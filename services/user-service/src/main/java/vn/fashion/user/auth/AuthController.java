package vn.fashion.user.auth;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public auth API (03 section 2.1). The refresh token only travels in an HttpOnly cookie. */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
  static final String REFRESH_COOKIE = "refresh_token";
  private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
  private static final Set<String> LOCALES = Set.of("vi", "en");

  private final AuthService auth;
  private final Tracer tracer;

  public AuthController(AuthService auth, Tracer tracer) {
    this.auth = auth;
    this.tracer = tracer;
  }

  public record RegisterRequest(String email, String password, String fullName, String locale) {
  }

  public record LoginRequest(String email, String password) {
  }

  public record SessionData(AuthService.UserView user, String accessToken, String tokenType, long expiresIn) {
  }

  /** Refresh only rotates tokens; it does not return the profile (contract RefreshResponse). */
  public record TokenData(String accessToken, String tokenType, long expiresIn) {
  }

  public record Metadata(String requestId, String traceId) {
  }

  public record ApiResponse<T>(String code, T data, Metadata metadata) {
  }

  public record FieldError(String field, String message) {
  }

  public record ApiError(String code, String message, List<FieldError> errors, Metadata metadata) {
  }

  @PostMapping("/register")
  public ResponseEntity<ApiResponse<SessionData>> register(@RequestBody RegisterRequest request) {
    String email = normalize(request.email());
    var errors = new ArrayList<FieldError>();
    if (email == null || email.length() > 254 || !EMAIL.matcher(email).matches()) {
      errors.add(new FieldError("email", "must be a valid e-mail address"));
    }
    validatePassword(request.password(), errors);
    if (request.fullName() == null || request.fullName().isBlank() || request.fullName().length() > 200) {
      errors.add(new FieldError("full_name", "must be 1..200 characters"));
    }
    if (request.locale() == null || !LOCALES.contains(request.locale())) {
      errors.add(new FieldError("locale", "must be vi or en"));
    }
    if (!errors.isEmpty()) {
      return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "INVALID_REGISTRATION", errors);
    }
    return session(HttpStatus.CREATED,
        auth.register(email, request.password(), request.fullName().strip(), request.locale()));
  }

  @PostMapping("/login")
  public ResponseEntity<ApiResponse<SessionData>> login(@RequestBody LoginRequest request) {
    String email = normalize(request.email());
    if (email == null || request.password() == null) {
      throw new AuthService.InvalidCredentialsException();
    }
    return session(HttpStatus.OK, auth.login(email, request.password()));
  }

  @PostMapping("/refresh")
  public ResponseEntity<ApiResponse<TokenData>> refresh(
      @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
    if (refreshToken == null || refreshToken.isBlank()) {
      throw new AuthService.InvalidRefreshTokenException();
    }
    AuthService.Session session = auth.refresh(refreshToken);
    return withCookie(HttpStatus.OK, session.refreshToken(),
        new TokenData(session.accessToken(), "Bearer", AccessTokenIssuer.TTL.toSeconds()));
  }

  @PostMapping("/logout")
  public ResponseEntity<Void> logout(@CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
    auth.logout(refreshToken);
    return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", 0).toString()).build();
  }

  @ExceptionHandler(AuthService.EmailTakenException.class)
  ResponseEntity<ApiError> emailTaken() {
    return error(HttpStatus.CONFLICT, "CONFLICT", "EMAIL_ALREADY_REGISTERED", List.of());
  }

  @ExceptionHandler(AuthService.InvalidCredentialsException.class)
  ResponseEntity<ApiError> invalidCredentials() {
    return error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "INVALID_CREDENTIALS", List.of());
  }

  @ExceptionHandler(AuthService.InvalidRefreshTokenException.class)
  ResponseEntity<ApiError> invalidRefresh() {
    ResponseEntity<ApiError> response =
        error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "INVALID_REFRESH_TOKEN", List.of());
    return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders())
        .header(HttpHeaders.SET_COOKIE, cookie("", 0).toString()).body(response.getBody());
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<ApiError> unreadable() {
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "MALFORMED_JSON", List.of());
  }

  private static void validatePassword(String password, List<FieldError> errors) {
    // BCrypt only uses the first 72 bytes; longer input would be silently truncated.
    if (password == null || password.length() < 8 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
      errors.add(new FieldError("password", "must be at least 8 characters and at most 72 bytes"));
    }
  }

  private static String normalize(String email) {
    return email == null ? null : email.strip().toLowerCase(java.util.Locale.ROOT);
  }

  private ResponseEntity<ApiResponse<SessionData>> session(HttpStatus status, AuthService.Session session) {
    return withCookie(status, session.refreshToken(), new SessionData(session.user(), session.accessToken(),
        "Bearer", AccessTokenIssuer.TTL.toSeconds()));
  }

  private <T> ResponseEntity<ApiResponse<T>> withCookie(HttpStatus status, String refreshToken, T data) {
    Metadata metadata = metadata();
    return ResponseEntity.status(status)
        .header("X-Correlation-Id", metadata.traceId())
        .header(HttpHeaders.SET_COOKIE, cookie(refreshToken, AuthService.REFRESH_TTL.toSeconds()).toString())
        .body(new ApiResponse<>("OK", data, metadata));
  }

  private <T> ResponseEntity<T> error(HttpStatus status, String code, String message, List<FieldError> errors) {
    Metadata metadata = metadata();
    @SuppressWarnings("unchecked")
    T body = (T) new ApiError(code, message, errors, metadata);
    return ResponseEntity.status(status).header("X-Correlation-Id", metadata.traceId()).body(body);
  }

  private static ResponseCookie cookie(String value, long maxAgeSeconds) {
    return ResponseCookie.from(REFRESH_COOKIE, value)
        .httpOnly(true).secure(true).sameSite("Strict").path("/api/v1/auth").maxAge(maxAgeSeconds)
        .build();
  }

  private Metadata metadata() {
    Span span = tracer.currentSpan();
    String traceId = span != null ? span.context().traceId() : UUID.randomUUID().toString().replace("-", "");
    return new Metadata(UUID.randomUUID().toString(), traceId);
  }
}
