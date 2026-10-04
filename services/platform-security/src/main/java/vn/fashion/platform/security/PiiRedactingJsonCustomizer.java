package vn.fashion.platform.security;

import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;

/**
 * Applies {@link LogRedactor} to every string value of Spring Boot structured (JSON) logs,
 * including message and stack trace. Enable per service with
 * {@code logging.structured.json.customizer=vn.fashion.platform.security.PiiRedactingJsonCustomizer}.
 */
public class PiiRedactingJsonCustomizer implements StructuredLoggingJsonMembersCustomizer<Object> {
  @Override
  public void customize(JsonWriter.Members<Object> members) {
    members.applyingValueProcessor(JsonWriter.ValueProcessor.of(String.class, LogRedactor::redact));
  }
}
