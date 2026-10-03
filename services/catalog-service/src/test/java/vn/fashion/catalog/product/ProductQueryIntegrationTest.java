package vn.fashion.catalog.product;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProductQueryIntegrationTest {
  @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11")
      .withDatabaseName("catalog")
      .withUsername("postgres")
      .withPassword("postgres")
      .withInitScript("catalog-test-init.sql");

  @Autowired
  private JdbcTemplate jdbc;

  @Autowired
  private ObjectMapper objectMapper;

  @LocalServerPort
  private int port;

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", () -> "catalog_runtime");
    registry.add("spring.datasource.password", () -> "catalog_runtime_test");
    registry.add("spring.flyway.url", postgres::getJdbcUrl);
    registry.add("spring.flyway.user", () -> "catalog_migration");
    registry.add("spring.flyway.password", () -> "catalog_migration_test");
  }

  @BeforeEach
  void clearProducts() {
    jdbc.execute("delete from products");
  }

  @Test
  void databaseSessionUsesUtc() {
    assertThat(jdbc.queryForObject("show timezone", String.class)).isEqualTo("UTC");
  }

  @Test
  void returnsOnlyActiveProducts() {
    jdbc.update(
        "insert into products(id,slug,status,name_vi,name_en,version) values (?,?,?,?,?,0)",
        UUID.fromString("00000000-0000-0000-0000-000000000001"),
        "visible",
        "ACTIVE",
        "Hiện",
        "Visible");
    jdbc.update(
        "insert into products(id,slug,status,name_vi,name_en,version) values (?,?,?,?,?,0)",
        UUID.fromString("00000000-0000-0000-0000-000000000002"),
        "hidden",
        "DRAFT",
        "Ẩn",
        "Hidden");

    var response = get("/api/v1/catalog/products?limit=1");
    var body = readProductPage(response);

    assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
    assertThat(body.code()).isEqualTo("OK");
    assertThat(body.data().items()).extracting(ProductSummary::slug)
        .containsExactly("visible");
    assertThat(body.data().nextCursor()).isNull();
    assertThat(body.metadata().requestId()).isNotBlank();
    assertThat(body.metadata().traceId()).isNotBlank();
  }

  @Test
  void rejectsLimitAbovePublicMaximum() {
    var response = get("/api/v1/catalog/products?limit=101");
    var body = readApiError(response);

    assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    assertThat(body.code()).isEqualTo("VALIDATION_ERROR");
    assertThat(body.message()).isEqualTo("INVALID_LIMIT");
    assertThat(body.errors()).singleElement().satisfies(error -> {
      assertThat(error.field()).isEqualTo("limit");
      assertThat(error.message()).isEqualTo("must be between 1 and 100");
      assertThat(error.rejectedValue()).isEqualTo(101);
    });
    assertThat(body.metadata().requestId()).isNotBlank();
    assertThat(body.metadata().traceId()).isNotBlank();
    assertThat(response.headers().firstValue("X-Correlation-Id"))
        .contains(body.metadata().traceId());
  }

  @Test
  void usesThePublicDefaultLimitOfTwenty() {
    for (int index = 0; index < 21; index++) {
      jdbc.update(
          "insert into products(id,slug,status,name_vi,name_en,version) values (?,?,?,?,?,0)",
          UUID.nameUUIDFromBytes(("product-" + index).getBytes(StandardCharsets.UTF_8)),
          "product-" + index,
          "ACTIVE",
          "Sản phẩm " + index,
          "Product " + index);
    }

    var response = get("/api/v1/catalog/products");
    var body = readProductPage(response);

    assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
    assertThat(body.data().items()).hasSize(20);
  }

  @Test
  void reportsReadinessAfterTheBaselineMigration() {
    var response = get("/actuator/health/readiness");

    assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
    assertThat(response.body()).contains("\"status\":\"UP\"");
  }

  @Test
  void runtimeRoleCannotCreateTables() {
    assertThatThrownBy(() -> jdbc.execute("create table forbidden(id bigint)"))
        .hasStackTraceContaining("permission denied");
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

  private ProductPageResponse readProductPage(HttpResponse<String> response) {
    return objectMapper.readValue(response.body(), ProductPageResponse.class);
  }

  private ApiErrorResponse readApiError(HttpResponse<String> response) {
    return objectMapper.readValue(response.body(), ApiErrorResponse.class);
  }

  record ProductPageResponse(String code, ProductPage data, Metadata metadata) {
  }

  record ProductPage(List<ProductSummary> items, @JsonProperty("next_cursor") String nextCursor) {
  }

  record ProductSummary(UUID id, String slug, @JsonProperty("name_vi") String nameVi,
                        @JsonProperty("name_en") String nameEn) {
  }

  record Metadata(@JsonProperty("request_id") String requestId,
                  @JsonProperty("trace_id") String traceId) {
  }

  record ApiErrorResponse(String code, String message, List<ApiFieldError> errors,
                          Metadata metadata) {
  }

  record ApiFieldError(String field, String message,
                       @JsonProperty("rejected_value") Integer rejectedValue) {
  }
}
