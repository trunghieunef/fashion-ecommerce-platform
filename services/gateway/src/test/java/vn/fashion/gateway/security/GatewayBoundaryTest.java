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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayBoundaryTest {
  private static final RecordingCatalogServer CATALOG = new RecordingCatalogServer();

  @LocalServerPort
  private int port;

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
  void neverRoutesInternalPath() {
    client.get()
        .uri("/internal/api/v1/platform/ping")
        .exchange()
        .expectStatus().isNotFound();

    assertThat(CATALOG.requestCount()).isZero();
  }

  private static final class RecordingCatalogServer implements HttpHandler {
    private final HttpServer server;
    private final AtomicInteger requestCount = new AtomicInteger();
    private Map<String, List<String>> headers = Map.of();

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

    private int requestCount() {
      return requestCount.get();
    }

    private Map<String, List<String>> headers() {
      return headers;
    }
  }
}
