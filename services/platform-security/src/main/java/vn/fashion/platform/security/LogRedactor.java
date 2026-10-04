package vn.fashion.platform.security;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Masks PII and secrets in log text (13 section 3, XCT-06): secret key/values, bearer tokens,
 * JWTs, e-mail addresses and Vietnamese phone numbers. A safety net only: code must still avoid
 * logging such data in the first place.
 */
// ponytail: regex masking of known shapes; names/addresses in free text are not detected.
public final class LogRedactor {
  private record Rule(Pattern pattern, String replacement) {
  }

  /** Key names whose value is a secret, including compound names (access_token, refreshToken). */
  private static final String SECRET_KEY =
      "[A-Za-z0-9_.-]*(?:password|passwd|secret|token|otp)[A-Za-z0-9_.-]*";

  /** A double- or single-quoted value (may contain spaces and escapes) or a bare token. */
  private static final String SECRET_VALUE =
      "(?:\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*'|[^\\s,;&}\"']+)";

  private static final Pattern SENSITIVE_NAME =
      Pattern.compile("(?i).*(password|passwd|secret|token|otp|authorization|cookie|credential).*");

  private static final List<Rule> RULES = List.of(
      // key=value, key: value and JSON "key":"value"; the separator is kept as written.
      new Rule(Pattern.compile("(?i)([\"']?)\\b(" + SECRET_KEY + ")\\1(\\s*[=:]\\s*)" + SECRET_VALUE),
          "$1$2$1$3[redacted]"),
      new Rule(Pattern.compile("(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]+"), "Bearer [redacted]"),
      new Rule(Pattern.compile("\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]*"), "[jwt]"),
      new Rule(Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+"), "[email]"),
      new Rule(Pattern.compile("(?<![\\w+])(\\+84|0)\\d{9}(?!\\d)"), "[phone]"));

  private LogRedactor() {
  }

  /** True for structured-log field names (MDC, key-values) whose whole value must be masked. */
  public static boolean isSensitiveName(String name) {
    return name != null && SENSITIVE_NAME.matcher(name).matches();
  }

  public static String redact(String text) {
    if (text == null) {
      return null;
    }
    String result = text;
    for (Rule rule : RULES) {
      result = rule.pattern().matcher(result).replaceAll(rule.replacement());
    }
    return result;
  }
}
