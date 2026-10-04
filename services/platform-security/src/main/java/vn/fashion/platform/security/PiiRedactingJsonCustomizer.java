package vn.fashion.platform.security;

import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;

/**
 * Masks Spring Boot structured (JSON) logs: any field whose name is sensitive (MDC entries, SLF4J
 * key-values; e.g. password, token, otp) is replaced whatever its type, and every other string
 * value, including message and stack trace, goes through {@link LogRedactor}. Enable per service
 * with {@code logging.structured.json.customizer=vn.fashion.platform.security.PiiRedactingJsonCustomizer}.
 */
public class PiiRedactingJsonCustomizer implements StructuredLoggingJsonMembersCustomizer<Object> {
  @Override
  public void customize(JsonWriter.Members<Object> members) {
    JsonWriter.ValueProcessor<Object> processor = (path, value) -> {
      if (path != null && LogRedactor.isSensitiveName(path.name())) {
        return "[redacted]";
      }
      return (value instanceof CharSequence text) ? LogRedactor.redact(text.toString()) : value;
    };
    members.applyingValueProcessor(processor);
  }
}
