package vn.fashion.catalog.admin;

import static org.assertj.core.api.Assertions.assertThat;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** TASK:PLT-05: an admin request's server span is stored with the outbox event it writes. */
class OutboxTraceParentIntegrationTest extends CatalogAdminTestSupport {
  @Test void adminRequestTraceIsStoredInOutbox() throws Exception {
    String trace = "0af7651916cd43dd8448eb211c80319c", parent = "b7ad6b7169203331";
    UUID product = createProduct("traced");
    var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/admin/api/v1/catalog/products/" + product + "/variants"))
        .header("Content-Type", "application/json").header("Authorization", "Bearer " + token("catalog.write"))
        .header("Idempotency-Key", UUID.randomUUID().toString()).header("traceparent", "00-" + trace + "-" + parent + "-01")
        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(variantInput("TRACED-M")))).build();
    var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(201);
    String stored = jdbc.queryForObject("select trace_parent from outbox_events where event_type='VARIANT_CREATED'", String.class);
    assertThat(stored).matches("00-" + trace + "-[0-9a-f]{16}-01").doesNotContain(parent);
  }
}
