package vn.fashion.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Only the actual socket peer grants permission to supply a forwarded IP. */
@SpringBootTest(properties = "fashion.security.trusted-proxies=localhost")
class ClientIpFilterTest {
  @Autowired ClientIdentityHeaderFilter filter;

  @Test
  void allowlistedProxyCanSupplyExactlyOneLiteralIp() {
    assertThat(forward("127.0.0.1", "203.0.113.7")).isEqualTo("203.0.113.7");
    assertThat(forward("127.0.0.1", "2001:db8::7")).isEqualTo("2001:db8:0:0:0:0:0:7");
  }

  @Test
  void nonAllowlistedPeerCannotSupplyForwardedIp() {
    assertThat(forward("127.0.0.2", "203.0.113.7")).isEqualTo("127.0.0.2");
  }

  @Test
  void duplicateForwardedHeadersFallBackToTheSocketPeer() {
    assertThat(forward("127.0.0.1", "203.0.113.7", "203.0.113.8")).isEqualTo("127.0.0.1");
  }

  @Test
  void malformedChainsAndHostnamesFallBackToTheSocketPeer() {
    for (String value : new String[]{"203.0.113.7, 203.0.113.8", "localhost", "999.1.1.1", "::1%lo", "127.1"}) {
      assertThat(forward("127.0.0.1", value)).isEqualTo("127.0.0.1");
    }
  }

  private String forward(String peer, String... forwarded) {
    var request = MockServerHttpRequest.post("/api/v1/auth/login")
        .remoteAddress(new InetSocketAddress(peer, 1234))
        .header("X-Forwarded-For", forwarded).header("X-Client-IP", "198.51.100.7").build();
    var result = new AtomicReference<ServerWebExchange>();
    filter.filter(MockServerWebExchange.from(request), exchange -> {
      result.set(exchange);
      return Mono.empty();
    }).block();
    return result.get().getRequest().getHeaders().getFirst("X-Client-IP");
  }
}
