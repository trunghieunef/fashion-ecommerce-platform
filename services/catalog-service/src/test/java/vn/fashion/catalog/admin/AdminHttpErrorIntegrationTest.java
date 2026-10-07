package vn.fashion.catalog.admin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class AdminHttpErrorIntegrationTest extends CatalogAdminTestSupport {
  @Test void unsupportedContentTypeKeeps415WithoutInternalErrorLog(CapturedOutput output) throws Exception {
    for (boolean authenticated : new boolean[]{false, true}) {
      var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/admin/api/v1/catalog/products"))
          .header("Content-Type", "text/plain").header("Idempotency-Key", UUID.randomUUID().toString());
      if (authenticated) request.header("Authorization", "Bearer " + token("catalog.write"));
      var response = HttpClient.newHttpClient().send(request.POST(HttpRequest.BodyPublishers.ofString("{}"))
          .build(), HttpResponse.BodyHandlers.ofString());
      assertHttpError(response, 415);
    }
    assertThat(output.getAll()).doesNotContain("Catalog admin request failed");
    assertThat(jdbc.queryForObject("select count(*) from products", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from audit_logs", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from idempotency_requests", Integer.class)).isZero();
  }

  @Test void unsupportedAcceptKeeps406WithoutInternalErrorLog(CapturedOutput output) throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/admin/api/v1/catalog/categories"))
        .header("Accept", "application/xml").header("Authorization", "Bearer " + token("catalog.write")).GET().build();
    var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    assertHttpError(response, 406);
    assertThat(output.getAll()).doesNotContain("Catalog admin request failed");
  }

  private void assertHttpError(HttpResponse<String> response, int status) {
    assertThat(response.statusCode()).isEqualTo(status);
    var error = json(response);
    assertThat(error.path("code").asText()).isEqualTo("VALIDATION_ERROR");
    UUID.fromString(error.path("metadata").path("request_id").asText());
    assertThat(error.path("metadata").path("trace_id").asText()).matches("[0-9a-f]{32}");
    assertThat(response.headers().firstValue("X-Correlation-Id")).contains(error.path("metadata").path("trace_id").asText());
    assertThat(response.body()).doesNotContain("HttpMediaType", "Exception", "stack_trace");
  }
}
