package vn.fashion.gateway.security;

import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import vn.fashion.platform.security.ProxyClientIp;

@Component
public class ClientIdentityHeaderFilter implements GlobalFilter, Ordered {
  private static final Set<String> UNTRUSTED_HEADERS = Set.of(
      "X-User-Id", "X-User-Roles", "X-Actor-Id", "X-Service-Name",
      "Forwarded", "X-Forwarded-For", "X-Real-IP", ProxyClientIp.HEADER);
  private final ProxyClientIp clientIp;

  public ClientIdentityHeaderFilter(@Value("${fashion.security.trusted-proxies:}") String proxies) {
    clientIp = new ProxyClientIp(proxies);
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    var request = exchange.getRequest();
    var forwarded = request.getHeaders().get("X-Forwarded-For");
    var remote = request.getRemoteAddress();
    // Without an actual socket address no forwarded identity can be authenticated.
    if (remote == null || remote.getAddress() == null) {
      return forward(exchange, chain, null);
    }
    String candidate = forwarded != null && forwarded.size() == 1 ? forwarded.getFirst() : null;
    var resolved = Mono.fromCallable(() -> clientIp.resolve(remote.getAddress().getHostAddress(), candidate));
    // Only configured proxy hostnames can need DNS; keep numeric/no-header paths on Netty.
    if (candidate != null && clientIp.requiresDns()) {
      resolved = resolved.subscribeOn(Schedulers.boundedElastic());
    }
    return resolved.flatMap(ip -> forward(exchange, chain, ip));
  }

  private Mono<Void> forward(ServerWebExchange exchange, GatewayFilterChain chain, String ip) {
    return chain.filter(exchange.mutate().request(exchange.getRequest().mutate().headers(headers -> {
          UNTRUSTED_HEADERS.forEach(headers::remove);
          if (ip != null) headers.set(ProxyClientIp.HEADER, ip);
        }).build()).build());
  }

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE;
  }
}
