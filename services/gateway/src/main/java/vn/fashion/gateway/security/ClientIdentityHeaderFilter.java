package vn.fashion.gateway.security;

import java.util.Set;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class ClientIdentityHeaderFilter implements GlobalFilter, Ordered {
  private static final Set<String> UNTRUSTED_HEADERS = Set.of(
      "X-User-Id", "X-User-Roles", "X-Actor-Id", "X-Service-Name");

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    var request = exchange.getRequest().mutate()
        .headers(headers -> UNTRUSTED_HEADERS.forEach(headers::remove))
        .build();
    return chain.filter(exchange.mutate().request(request).build());
  }

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE;
  }
}
