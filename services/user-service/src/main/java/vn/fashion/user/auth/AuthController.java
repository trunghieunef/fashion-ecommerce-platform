package vn.fashion.user.auth;

import io.micrometer.tracing.Tracer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.fashion.user.web.Api;
import vn.fashion.user.web.MemberAuth;

/** Public auth API (03 section 2.1). The refresh token only travels in an HttpOnly cookie. */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
  static final String REFRESH_COOKIE = "refresh_token";
  private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
  private static final Set<String> LOCALES = Set.of("vi", "en");

  private final AuthService auth;
  private final PasswordService passwords;
  private final MemberAuth members;
  private final Tracer tracer;

  public AuthController(AuthService auth, PasswordService passwords, MemberAuth members, Tracer tracer) {
    this.auth = auth;
    this.passwords = passwords;
    this.members = members;
    this.tracer = tracer;
  }

  public record RegisterRequest(String email, String password, String fullName, String locale) {
  }

  public record LoginRequest(String email, String password) {
  }

  public record SessionData(AuthService.UserView user, String accessToken, String tokenType, long expiresIn) {
  }

  public record PasswordChange(String currentPassword, String newPassword) {
  }

  public record ForgotRequest(String email) {
  }

  public record ResetRequest(String token, String newPassword) {
  }

  /** The same answer whether or not the e-mail has an account (PRD USR-03). */
  public record ForgotAccepted(String message) {
  }

  /** Refresh only rotates tokens; it does not return the profile (contract RefreshResponse). */
  public record TokenData(String accessToken, String tokenType, long expiresIn) {
  }

  @PostMapping("/register")
  public ResponseEntity<?> register(@RequestBody RegisterRequest request) {
    String email = normalize(request.email());
    var errors = new ArrayList<Api.FieldError>();
    if (email == null || email.length() > 254 || !EMAIL.matcher(email).matches()) {
      errors.add(new Api.FieldError("email", "must be a valid e-mail address"));
    }
    validatePassword("password", request.password(), errors);
    if (request.fullName() == null || request.fullName().isBlank() || request.fullName().length() > 200) {
      errors.add(new Api.FieldError("full_name", "must be 1..200 characters"));
    }
    if (request.locale() == null || !LOCALES.contains(request.locale())) {
      errors.add(new Api.FieldError("locale", "must be vi or en"));
    }
    if (!errors.isEmpty()) {
      return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "INVALID_REGISTRATION", errors);
    }
    return session(HttpStatus.CREATED,
        auth.register(email, request.password(), request.fullName().strip(), request.locale()));
  }

  @PostMapping("/login")
  public ResponseEntity<Api.Response<SessionData>> login(@RequestBody LoginRequest request) {
    String email = normalize(request.email());
    if (email == null || request.password() == null) {
      throw new AuthService.InvalidCredentialsException();
    }
    return session(HttpStatus.OK, auth.login(email, request.password()));
  }

  @PostMapping("/refresh")
  public ResponseEntity<Api.Response<TokenData>> refresh(
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

  /** Revokes every session including the caller's; the client signs in again. */
  @PostMapping("/password/change")
  public ResponseEntity<Void> changePassword(
      @RequestHeader(name = "Authorization", required = false) String authorization,
      @RequestBody PasswordChange request) {
    var userId = members.requireMember(authorization);
    var errors = new ArrayList<Api.FieldError>();
    if (request.currentPassword() == null || request.currentPassword().isEmpty()) {
      errors.add(new Api.FieldError("current_password", "is required"));
    }
    validatePassword("new_password", request.newPassword(), errors);
    requireValid(errors, "INVALID_PASSWORD_CHANGE");
    passwords.change(userId, request.currentPassword(), request.newPassword());
    return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", 0).toString()).build();
  }

  @PostMapping("/password/forgot")
  public ResponseEntity<Api.Response<ForgotAccepted>> forgotPassword(@RequestBody ForgotRequest request) {
    String email = normalize(request.email());
    if (email == null || email.length() > 254 || !EMAIL.matcher(email).matches()) {
      requireValid(List.of(new Api.FieldError("email", "must be a valid e-mail address")), "INVALID_EMAIL");
    }
    passwords.forgot(email);
    return Api.ok(HttpStatus.ACCEPTED, new ForgotAccepted("RESET_EMAIL_REQUESTED"), tracer);
  }

  @PostMapping("/password/reset")
  public ResponseEntity<Void> resetPassword(@RequestBody ResetRequest request) {
    var errors = new ArrayList<Api.FieldError>();
    if (request.token() == null || request.token().isBlank() || request.token().length() > 128) {
      errors.add(new Api.FieldError("token", "is required"));
    }
    validatePassword("new_password", request.newPassword(), errors);
    requireValid(errors, "INVALID_PASSWORD_RESET");
    passwords.reset(request.token(), request.newPassword());
    return ResponseEntity.noContent().build();
  }

  @ExceptionHandler(AuthService.EmailTakenException.class)
  ResponseEntity<Api.Error> emailTaken() {
    return error(HttpStatus.CONFLICT, "CONFLICT", "EMAIL_ALREADY_REGISTERED", List.of());
  }

  @ExceptionHandler(AuthService.InvalidCredentialsException.class)
  ResponseEntity<Api.Error> invalidCredentials() {
    return error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "INVALID_CREDENTIALS", List.of());
  }

  @ExceptionHandler(AuthService.InvalidRefreshTokenException.class)
  ResponseEntity<Api.Error> invalidRefresh() {
    ResponseEntity<Api.Error> response =
        Api.error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "INVALID_REFRESH_TOKEN", List.of(), tracer);
    return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders())
        .header(HttpHeaders.SET_COOKIE, cookie("", 0).toString()).body(response.getBody());
  }

  private static void requireValid(List<Api.FieldError> errors, String message) {
    if (!errors.isEmpty()) {
      throw new Api.Problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, errors);
    }
  }

  private static void validatePassword(String field, String password, List<Api.FieldError> errors) {
    // BCrypt only uses the first 72 bytes; longer input would be silently truncated.
    if (password == null || password.length() < 8 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
      errors.add(new Api.FieldError(field, "must be at least 8 characters and at most 72 bytes"));
    }
  }

  private static String normalize(String email) {
    return email == null ? null : email.strip().toLowerCase(java.util.Locale.ROOT);
  }

  private ResponseEntity<Api.Response<SessionData>> session(HttpStatus status, AuthService.Session session) {
    return withCookie(status, session.refreshToken(), new SessionData(session.user(), session.accessToken(),
        "Bearer", AccessTokenIssuer.TTL.toSeconds()));
  }

  private <T> ResponseEntity<Api.Response<T>> withCookie(HttpStatus status, String refreshToken, T data) {
    ResponseEntity<Api.Response<T>> response = Api.ok(status, data, tracer);
    return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders())
        .header(HttpHeaders.SET_COOKIE, cookie(refreshToken, AuthService.REFRESH_TTL.toSeconds()).toString())
        .body(response.getBody());
  }

  private ResponseEntity<Api.Error> error(HttpStatus status, String code, String message,
                                          List<Api.FieldError> errors) {
    return Api.error(status, code, message, errors, tracer);
  }

  private static ResponseCookie cookie(String value, long maxAgeSeconds) {
    return ResponseCookie.from(REFRESH_COOKIE, value)
        .httpOnly(true).secure(true).sameSite("Strict").path("/api/v1/auth").maxAge(maxAgeSeconds)
        .build();
  }

}
