package vn.fashion.user.profile;

import io.micrometer.tracing.Tracer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.fashion.user.web.Api;
import vn.fashion.user.web.MemberAuth;

/** Member profile and addresses (03 section 2.1). Another member's address answers 404. */
@RestController
@RequestMapping("/api/v1/users/me")
public class ProfileController {
  private static final Pattern VN_PHONE = Pattern.compile("^(0|\\+84)\\d{9}$");
  private static final Set<String> LOCALES = Set.of("vi", "en");

  private final ProfileService profiles;
  private final MemberAuth auth;
  private final Tracer tracer;

  public ProfileController(ProfileService profiles, MemberAuth auth, Tracer tracer) {
    this.profiles = profiles;
    this.auth = auth;
    this.tracer = tracer;
  }

  public record ProfileUpdate(String fullName, String locale) {
  }

  @GetMapping
  public ResponseEntity<Api.Response<ProfileService.Profile>> me(
      @RequestHeader(name = "Authorization", required = false) String authorization) {
    return Api.ok(HttpStatus.OK, profiles.profile(auth.requireMember(authorization)), tracer);
  }

  @PutMapping
  public ResponseEntity<Api.Response<ProfileService.Profile>> update(
      @RequestHeader(name = "Authorization", required = false) String authorization,
      @RequestBody ProfileUpdate request) {
    UUID userId = auth.requireMember(authorization);
    var errors = new ArrayList<Api.FieldError>();
    if (request.fullName() == null || request.fullName().isBlank() || request.fullName().length() > 200) {
      errors.add(new Api.FieldError("full_name", "must be 1..200 characters"));
    }
    if (request.locale() == null || !LOCALES.contains(request.locale())) {
      errors.add(new Api.FieldError("locale", "must be vi or en"));
    }
    requireValid(errors, "INVALID_PROFILE");
    return Api.ok(HttpStatus.OK, profiles.update(userId, request.fullName().strip(), request.locale()), tracer);
  }

  @GetMapping("/addresses")
  public ResponseEntity<Api.Response<List<ProfileService.Address>>> addresses(
      @RequestHeader(name = "Authorization", required = false) String authorization) {
    return Api.ok(HttpStatus.OK, profiles.addresses(auth.requireMember(authorization)), tracer);
  }

  @PostMapping("/addresses")
  public ResponseEntity<Api.Response<ProfileService.Address>> create(
      @RequestHeader(name = "Authorization", required = false) String authorization,
      @RequestBody ProfileService.AddressInput input) {
    UUID userId = auth.requireMember(authorization);
    validate(input);
    return Api.ok(HttpStatus.CREATED, profiles.create(userId, input), tracer);
  }

  @PutMapping("/addresses/{id}")
  public ResponseEntity<Api.Response<ProfileService.Address>> update(
      @RequestHeader(name = "Authorization", required = false) String authorization,
      @PathVariable UUID id, @RequestBody ProfileService.AddressInput input) {
    UUID userId = auth.requireMember(authorization);
    validate(input);
    return Api.ok(HttpStatus.OK, profiles.update(userId, id, input), tracer);
  }

  @DeleteMapping("/addresses/{id}")
  public ResponseEntity<Void> delete(
      @RequestHeader(name = "Authorization", required = false) String authorization, @PathVariable UUID id) {
    profiles.delete(auth.requireMember(authorization), id);
    return ResponseEntity.noContent().build();
  }

  private static void validate(ProfileService.AddressInput input) {
    var errors = new ArrayList<Api.FieldError>();
    requireText(errors, "recipient_name", input.recipientName(), 100);
    if (input.phone() == null || !VN_PHONE.matcher(input.phone()).matches()) {
      errors.add(new Api.FieldError("phone", "must be a Vietnamese phone number (0 or +84 and 9 digits)"));
    }
    requireText(errors, "province_code", input.provinceCode(), 10);
    if (input.districtCode() != null && input.districtCode().length() > 10) {
      errors.add(new Api.FieldError("district_code", "must be at most 10 characters"));
    }
    requireText(errors, "ward_code", input.wardCode(), 10);
    requireText(errors, "address_line", input.addressLine(), 255);
    requireValid(errors, "INVALID_ADDRESS");
  }

  private static void requireText(List<Api.FieldError> errors, String field, String value, int max) {
    if (value == null || value.isBlank() || value.length() > max) {
      errors.add(new Api.FieldError(field, "must be 1.." + max + " characters"));
    }
  }

  private static void requireValid(List<Api.FieldError> errors, String message) {
    if (!errors.isEmpty()) {
      throw new Api.Problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, errors);
    }
  }
}
