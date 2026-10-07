package vn.fashion.catalog.product;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "spring.flyway.enabled=false")
class ReadinessWithoutMigrationIntegrationTest {
  @Container
  static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11")
      .withDatabaseName("catalog")
      .withUsername("postgres")
      .withPassword("postgres")
      .withInitScript("catalog-test-init.sql");

  @LocalServerPort
  private int port;

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("fashion.catalog.jwt.public-keys", vn.fashion.catalog.admin.CatalogAdminTestSupport::publicKeys);
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", () -> "catalog_runtime");
    registry.add("spring.datasource.password", () -> "catalog_runtime_test");
  }

  @Test
  void reportsServiceUnavailableWhenBaselineMigrationHasNotRun() {
    var response = get("/actuator/health/readiness");

    assertThat(response.statusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
    assertThat(response.body()).contains("\"status\":\"DOWN\"");
  }

  private HttpResponse<String> get(String path) {
    try {
      return HttpClient.newHttpClient().send(
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
          HttpResponse.BodyHandlers.ofString());
    } catch (IOException exception) {
      throw new AssertionError(exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError(exception);
    }
  }
}
