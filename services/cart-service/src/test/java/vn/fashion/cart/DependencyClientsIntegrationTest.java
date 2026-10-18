package vn.fashion.cart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import vn.fashion.cart.deps.CatalogClient;
import vn.fashion.cart.deps.DependencyUnavailable;
import vn.fashion.cart.deps.StockClient;
import vn.fashion.platform.security.ServiceTokenVerifier;

class DependencyClientsIntegrationTest extends CartTestSupport {
  @Autowired CatalogClient catalog;
  @Autowired StockClient stock;
  final UUID variant = UUID.randomUUID(), gone = UUID.randomUUID();

  @BeforeEach void stubs() { StubServers.CATALOG.reset(); StubServers.INVENTORY.reset(); }

  @Test void quoteSendsServiceTokenAndParses() throws Exception {
    StubServers.CATALOG.reply(s -> new StubServers.Reply(200, StubServers.ok("[" + StubServers.quoteItem(variant.toString(), true, "TEE-M", 100000, 3)
        + "," + StubServers.missing(gone.toString()) + "]")));
    var quotes = catalog.quote(List.of(variant, gone));
    assertThat(quotes.get(variant)).isEqualTo(new CatalogClient.Quote(variant, true, true, "TEE-M", 100000, 3));
    assertThat(quotes.get(gone).found()).isFalse();
    var seen = StubServers.CATALOG.seen.getFirst();
    assertThat(seen.path()).isEqualTo("/internal/api/v1/catalog/variants/quote");
    assertThat(seen.body()).contains(variant.toString()).contains("variant_ids");
    var verifier = new ServiceTokenVerifier("catalog-service", Map.of("cart-service", KEY.toECPublicKey()), Clock.systemUTC());
    assertThat(verifier.verify(seen.authorization().substring("Bearer ".length()))).contains("cart-service");
  }

  @Test void stockParsesAndUsesInventoryAudience() throws Exception {
    StubServers.INVENTORY.reply(s -> new StubServers.Reply(200, StubServers.stock("TEE-M", 4)));
    assertThat(stock.stock("TEE-M")).hasValueSatisfying(v -> assertThat(v.available()).isEqualTo(4));
    var seen = StubServers.INVENTORY.seen.getFirst();
    assertThat(seen.path()).isEqualTo("/internal/api/v1/inventory/skus/TEE-M");
    var verifier = new ServiceTokenVerifier("inventory-service", Map.of("cart-service", KEY.toECPublicKey()), Clock.systemUTC());
    assertThat(verifier.verify(seen.authorization().substring("Bearer ".length()))).contains("cart-service");
  }

  @Test void unknownSkuIsEmpty() {
    StubServers.INVENTORY.reply(s -> new StubServers.Reply(404, "{\"code\":\"NOT_FOUND\",\"message\":\"SKU_NOT_FOUND\"}"));
    assertThat(stock.stock("NOPE")).isEmpty();
  }

  @Test void timeoutIsUnavailable() {
    StubServers.CATALOG.reply(s -> new StubServers.Reply(200, StubServers.ok("[" + StubServers.quoteItem(variant.toString(), true, "TEE-M", 1, 1) + "]")));
    catalog.quote(List.of(variant)); // warm up signing/HTTP client so the bound measures the 1 s timeout only
    StubServers.CATALOG.delay(2000);
    long start = System.nanoTime();
    assertThatThrownBy(() -> catalog.quote(List.of(variant))).isInstanceOf(DependencyUnavailable.class);
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1500));
  }

  @Test void badShapeIsUnavailable() {
    StubServers.CATALOG.reply(s -> new StubServers.Reply(200, "{\"data\":\"x\"}"));
    assertThatThrownBy(() -> catalog.quote(List.of(variant))).isInstanceOf(DependencyUnavailable.class);
    StubServers.INVENTORY.reply(s -> new StubServers.Reply(200, "not json"));
    assertThatThrownBy(() -> stock.stock("TEE-M")).isInstanceOf(DependencyUnavailable.class);
  }

  @Test void serverErrorIsUnavailable() {
    StubServers.CATALOG.reply(s -> new StubServers.Reply(503, "{}"));
    assertThatThrownBy(() -> catalog.quote(List.of(variant))).isInstanceOf(DependencyUnavailable.class);
    StubServers.INVENTORY.reply(s -> new StubServers.Reply(500, "{}"));
    assertThatThrownBy(() -> stock.stock("TEE-M")).isInstanceOf(DependencyUnavailable.class);
  }
}
