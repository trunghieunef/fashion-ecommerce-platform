package vn.fashion.catalog.admin;

import static org.assertj.core.api.Assertions.assertThat;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** TASK:PLT-05: OTLP export only when an endpoint is configured; an unreachable Tempo never slows requests. */
class TracingExportIntegrationTest {
  static boolean hasOtlpExporter(ApplicationContext context) {
    return Arrays.stream(context.getBeanNamesForType(SpanExporter.class))
        .map(name -> context.getBean(name).getClass().getName()).anyMatch(n -> n.contains("Otlp"));
  }

  @Nested class WithoutEndpoint extends CatalogAdminTestSupport {
    @Autowired ApplicationContext context;
    @Test void noExporterAndDefaultSampling() {
      assertThat(hasOtlpExporter(context)).isFalse();
      assertThat(context.getEnvironment().getProperty("management.tracing.sampling.probability")).isEqualTo("0.1");
    }
  }

  @Nested class WithUnreachableEndpoint extends CatalogAdminTestSupport {
    @DynamicPropertySource static void otlp(DynamicPropertyRegistry registry) {
      registry.add("management.opentelemetry.tracing.export.otlp.endpoint", () -> "http://127.0.0.1:1/v1/traces");
      registry.add("TRACING_SAMPLING_PROBABILITY", () -> "1.0");
    }
    @Autowired ApplicationContext context;
    @Test void exporterConfiguredAndRequestsStayFast() {
      assertThat(hasOtlpExporter(context)).isTrue();
      long start = System.nanoTime();
      for (int i = 0; i < 5; i++) assertThat(send("GET", "/api/v1/catalog/categories", null, null, null).statusCode()).isEqualTo(200);
      assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
      var readiness = send("GET", "/actuator/health/readiness", null, null, null);
      assertThat(readiness.statusCode()).isEqualTo(200);
    }
  }
}
