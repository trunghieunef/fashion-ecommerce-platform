package vn.fashion.gateway.security;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * CSRF for cookie-authenticated mutations (ADR-20, 03 section 1.1): the browser Origin must be
 * allowlisted; without Origin only Sec-Fetch-Site same-origin passes; neither fails closed.
 * Requests without cookies (Bearer only) cannot be forged by a browser and are not checked.
 */
@Component
public class CsrfOriginFilter implements GlobalFilter, Ordered {
  private static final Set<HttpMethod> SAFE_METHODS =
      Set.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS, HttpMethod.TRACE);

  private final Set<String> allowedOrigins;

  public CsrfOriginFilter(@Value("${fashion.security.allowed-origins:}") List<String> allowedOrigins) {
    this.allowedOrigins = Set.copyOf(allowedOrigins);
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    HttpHeaders headers = exchange.getRequest().getHeaders();
    if (SAFE_METHODS.contains(exchange.getRequest().getMethod()) || !headers.containsHeader(HttpHeaders.COOKIE)) {
      return chain.filter(exchange);
    }
    String origin = headers.getOrigin();
    boolean allowed = origin != null
        ? allowedOrigins.contains(origin)
        : "same-origin".equals(headers.getFirst("Sec-Fetch-Site"));
    return allowed ? chain.filter(exchange) : forbidden(exchange);
  }

  private static Mono<Void> forbidden(ServerWebExchange exchange) {
    String traceId = UUID.randomUUID().toString();
    byte[] body = ("{\"code\":\"FORBIDDEN\",\"message\":\"CSRF_ORIGIN_REJECTED\",\"metadata\":{\"request_id\":\""
        + UUID.randomUUID() + "\",\"trace_id\":\"" + traceId + "\"}}").getBytes(StandardCharsets.UTF_8);
    var response = exchange.getResponse();
    response.setStatusCode(HttpStatus.FORBIDDEN);
    response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
    response.getHeaders().set("X-Correlation-Id", traceId);
    return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
  }

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE + 1;
  }
}
