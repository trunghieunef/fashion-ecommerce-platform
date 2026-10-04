package vn.fashion.gateway.security;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayBoundaryTest {
  private static final RecordingCatalogServer CATALOG = new RecordingCatalogServer();

  @LocalServerPort
  private int port;

  @LocalManagementPort
  private int managementPort;

  private WebTestClient client;

  @BeforeEach
  void setUpClient() {
    CATALOG.reset();
    client = WebTestClient.bindToServer()
        .baseUrl("http://localhost:" + port)
        .build();
  }

  @DynamicPropertySource
  static void catalogBaseUrl(DynamicPropertyRegistry registry) {
    registry.add("CATALOG_BASE_URL", CATALOG::baseUrl);
    // The recording stub also stands in for user-service; tests check the forwarded path.
    registry.add("USER_BASE_URL", CATALOG::baseUrl);
    registry.add("fashion.security.allowed-origins", () -> "http://localhost:4173");
    registry.add("GATEWAY_MANAGEMENT_PORT", () -> "0");
  }

  @BeforeAll
  static void startCatalog() {
    CATALOG.start();
  }

  @AfterAll
  static void stopCatalog() {
    CATALOG.stop();
  }

  @Test
  void removesBrowserIdentityHeadersBeforeForwardingCatalogRequest() {
    client.get()
        .uri("/api/v1/catalog/products?limit=1")
        .header("X-User-Id", "attacker")
        .header("X-User-Roles", "SUPER_ADMIN")
        .header("X-Actor-Id", "attacker")
        .header("X-Service-Name", "browser")
        .exchange()
        .expectStatus().isOk();

    assertThat(CATALOG.requestCount()).isEqualTo(1);
    assertThat(CATALOG.headers()).doesNotContainKeys(
        "x-user-id", "x-user-roles", "x-actor-id", "x-service-name");
  }

  @Test
  void routesAuthRequestsToUserService() {
    client.post().uri("/api/v1/auth/login").header("Content-Type", "application/json")
        .bodyValue("{}").exchange().expectStatus().isOk();

    assertThat(CATALOG.requestCount()).isEqualTo(1);
    assertThat(CATALOG.path()).isEqualTo("/api/v1/auth/login");
  }

  @Test
  void neverRoutesInternalPath() {
    client.get()
        .uri("/internal/api/v1/platform/ping")
        .exchange()
        .expectStatus().isNotFound();

    assertThat(CATALOG.requestCount()).isZero();
  }

  @Test
  void crossOriginCookieMutationIsRejectedWithoutForwarding() {
    cookiePost().header("Origin", "https://evil.example").exchange()
        .expectStatus().isForbidden()
        .expectBody().jsonPath("$.code").isEqualTo("FORBIDDEN")
        .jsonPath("$.metadata.request_id").exists();

    assertThat(CATALOG.requestCount()).isZero();
  }

  @Test
  void cookieMutationWithoutOriginOrFetchMetadataFailsClosed() {
    cookiePost().exchange().expectStatus().isForbidden();
    assertThat(CATALOG.requestCount()).isZero();
  }

  @Test
  void crossSiteFetchMetadataIsRejected() {
    cookiePost().header("Sec-Fetch-Site", "cross-site").exchange().expectStatus().isForbidden();
    assertThat(CATALOG.requestCount()).isZero();
  }

  @Test
  void opaqueNullOriginIsRejected() {
    cookiePost().header("Origin", "null").exchange().expectStatus().isForbidden();
    assertThat(CATALOG.requestCount()).isZero();
  }

  @Test
  void allowlistedOriginCookieMutationIsForwarded() {
    cookiePost().header("Origin", "http://localhost:4173").exchange().expectStatus().isOk();
    assertThat(CATALOG.requestCount()).isEqualTo(1);
  }

  @Test
  void sameOriginFetchMetadataWithoutOriginIsForwarded() {
    cookiePost().header("Sec-Fetch-Site", "same-origin").exchange().expectStatus().isOk();
    assertThat(CATALOG.requestCount()).isEqualTo(1);
  }

  @Test
  void bearerMutationWithoutCookieIsNotCsrfAndIsForwarded() {
    client.post().uri("/api/v1/catalog/products").header("Authorization", "Bearer synthetic")
        .header("Origin", "https://evil.example").exchange().expectStatus().isOk();
    assertThat(CATALOG.requestCount()).isEqualTo(1);
  }

  @Test
  void safeMethodWithCookieIsNotBlocked() {
    client.get().uri("/api/v1/catalog/products?limit=1").header("Cookie", "guest_cart=synthetic")
        .header("Origin", "https://evil.example").exchange().expectStatus().isOk();
    assertThat(CATALOG.requestCount()).isEqualTo(1);
  }

  @Test
  void startsAW3cTraceAndPropagatesItToTheUpstream() {
    client.get().uri("/api/v1/catalog/products?limit=1").exchange().expectStatus().isOk();

    assertThat(CATALOG.headers().get("traceparent"))
        .singleElement().asString().matches("00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]");
  }

  @Test
  void continuesTheCallersTraceTowardsTheUpstream() {
    String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
    client.get().uri("/api/v1/catalog/products?limit=1")
        .header("traceparent", "00-" + traceId + "-00f067aa0ba902b7-01")
        .exchange().expectStatus().isOk();

    assertThat(CATALOG.headers().get("traceparent")).singleElement().asString()
        .startsWith("00-" + traceId + "-");
  }

  @Test
  void applicationLogsAreJsonAndRedacted(CapturedOutput output) {
    LoggerFactory.getLogger(GatewayBoundaryTest.class)
        .info("customer person@example.test sent Bearer synthetic.token.value password=hunter2");

    String line = output.getOut().lines().filter(l -> l.contains("customer [email]"))
        .findFirst().orElseThrow(() -> new AssertionError("redacted JSON log line not found"));
    assertThat(line).startsWith("{").contains("\"log\":{\"level\":\"INFO\"")
        .contains("Bearer [redacted]").contains("password=[redacted]");
    assertThat(output.getAll()).doesNotContain("person@example.test", "synthetic.token.value", "hunter2");
  }

  @Test
  void sensitiveMdcAndKeyValueFieldsAreRedactedByName(CapturedOutput output) {
    var logger = LoggerFactory.getLogger(GatewayBoundaryTest.class);
    MDC.put("password", "SYNTH_PASSWORD_VALUE");
    try {
      logger.atInfo().addKeyValue("token", "SYNTH_TOKEN_VALUE").addKeyValue("otp", 123456)
          .log("structured field redaction probe");
    } finally {
      MDC.remove("password");
    }

    String line = output.getOut().lines().filter(l -> l.contains("structured field redaction probe"))
        .findFirst().orElseThrow(() -> new AssertionError("JSON log line not found"));
    assertThat(line).contains("\"password\":\"[redacted]\"").contains("\"token\":\"[redacted]\"")
        .contains("\"otp\":\"[redacted]\"");
    assertThat(output.getAll()).doesNotContain("SYNTH_PASSWORD_VALUE", "SYNTH_TOKEN_VALUE", "123456");
  }

  @Test
  void exposesRedMetricsOnTheManagementPortWithoutHighCardinalityLabels() {
    client.get().uri("/api/v1/catalog/products?limit=1").exchange().expectStatus().isOk();

    String metrics = WebTestClient.bindToServer().baseUrl("http://localhost:" + managementPort).build()
        .get().uri("/actuator/prometheus").exchange()
        .expectStatus().isOk()
        .expectBody(String.class).returnResult().getResponseBody();
    assertThat(metrics).contains("http_server_requests_seconds_count")
        .doesNotContain("limit=1");
    client.get().uri("/actuator/prometheus").exchange().expectStatus().isNotFound();
  }

  @Test
  void actuatorIsNotServedOnThePublicPort() {
    client.get().uri("/actuator/health").exchange().expectStatus().isNotFound();
    client.get().uri("/actuator/metrics").exchange().expectStatus().isNotFound();
    assertThat(managementPort).isNotEqualTo(port);
  }

  @Test
  void readinessIsServedOnTheManagementPort() {
    WebTestClient.bindToServer().baseUrl("http://localhost:" + managementPort).build()
        .get().uri("/actuator/health/readiness").exchange().expectStatus().isOk();
  }

  private WebTestClient.RequestHeadersSpec<?> cookiePost() {
    return client.post().uri("/api/v1/catalog/products").header("Cookie", "guest_cart=synthetic");
  }

  private static final class RecordingCatalogServer implements HttpHandler {
    private final HttpServer server;
    private final AtomicInteger requestCount = new AtomicInteger();
    private Map<String, List<String>> headers = Map.of();
    private volatile String path;

    private RecordingCatalogServer() {
      try {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this);
      } catch (IOException exception) {
        throw new IllegalStateException("Cannot create catalog test server", exception);
      }
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
      headers = new ConcurrentHashMap<>();
      exchange.getRequestHeaders().forEach((name, values) ->
          headers.put(name.toLowerCase(), List.copyOf(values)));
      path = exchange.getRequestURI().getPath();
      requestCount.incrementAndGet();
      byte[] response = "{\"code\":\"OK\",\"data\":{\"items\":[],\"next_cursor\":null},\"metadata\":{\"request_id\":\"test\",\"trace_id\":\"test\"}}"
          .getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, response.length);
      exchange.getResponseBody().write(response);
      exchange.close();
    }

    private String baseUrl() {
      return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void start() {
      server.start();
    }

    private void stop() {
      server.stop(0);
    }

    private void reset() {
      requestCount.set(0);
      headers = Map.of();
    }

    private String path() {
      return path;
    }

    private int requestCount() {
      return requestCount.get();
    }

    private Map<String, List<String>> headers() {
      return headers;
    }
  }
}
