package vn.fashion.cart.cart;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import vn.fashion.cart.deps.CatalogClient;
import vn.fashion.cart.deps.StockClient;

/** Spec section 6: one quote batch plus per-SKU stock, in parallel; any failure → UNAVAILABLE, never an error. */
@Component
public class CartPreview {
  public record Result(Map<UUID, CartView.Preview> byItem, String status) { }
  private final CatalogClient catalog;
  private final StockClient stock;
  private final Duration timeout;
  // ponytail: fixed pool of 8 shared by all reads; size it from measured traffic before production.
  private final ExecutorService pool = Executors.newFixedThreadPool(8);

  public CartPreview(CatalogClient catalog, StockClient stock, @Value("${fashion.cart.dependency-timeout:1s}") Duration timeout) {
    this.catalog = catalog; this.stock = stock; this.timeout = timeout;
  }

  public Result load(List<CartRepository.CartItemRow> items) {
    if (items.isEmpty()) return new Result(Map.of(), "EMPTY");
    try {
      var quotes = CompletableFuture.supplyAsync(() -> catalog.quote(items.stream().map(CartRepository.CartItemRow::variantId).toList()), pool);
      var stocks = new HashMap<String, CompletableFuture<Optional<StockClient.Stock>>>();
      for (var item : items) stocks.computeIfAbsent(item.sku(), sku -> CompletableFuture.supplyAsync(() -> stock.stock(sku), pool));
      long waitMillis = timeout.toMillis() * 2;
      var quoted = quotes.get(waitMillis, TimeUnit.MILLISECONDS);
      var result = new HashMap<UUID, CartView.Preview>();
      for (var item : items) {
        var quote = quoted.get(item.variantId());
        var observed = stocks.get(item.sku()).get(waitMillis, TimeUnit.MILLISECONDS);
        boolean active = quote != null && quote.found() && quote.active();
        long price = quote != null && quote.found() ? quote.effectivePrice() : item.priceSnapshot();
        result.put(item.id(), new CartView.Preview(price, price != item.priceSnapshot(), active,
            observed.map(StockClient.Stock::available).orElse(0), observed.map(StockClient.Stock::observedAt).orElse(null)));
      }
      return new Result(result, "OK");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new Result(Map.of(), "UNAVAILABLE");
    } catch (Exception e) {
      return new Result(Map.of(), "UNAVAILABLE");
    }
  }
}
