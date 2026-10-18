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
import vn.fashion.cart.deps.DependencyUnavailable;
import vn.fashion.cart.deps.StockClient;

/** Spec section 6: one quote batch plus per-SKU stock, in parallel; any failure → UNAVAILABLE, never an error. */
@Component
public class CartPreview {
  public record Result(Map<UUID, CartView.Preview> byItem, String status) { }
  private final CatalogClient catalog;
  private final StockClient stock;
  private final Duration timeout;
  // Virtual threads: 100 blocking stock reads of one cart never queue behind other carts (review I2).
  // ponytail: one HTTP call per SKU; switch to a stock batch endpoint if inventory adds one.
  private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

  public CartPreview(CatalogClient catalog, StockClient stock, @Value("${fashion.cart.dependency-timeout:1s}") Duration timeout) {
    this.catalog = catalog; this.stock = stock; this.timeout = timeout;
  }

  @jakarta.annotation.PreDestroy void close() { pool.shutdownNow(); }

  /** Catalog quotes by variant and stock by stored SKU. */
  public record Fetched(Map<UUID, CatalogClient.Quote> quotes, Map<String, Optional<StockClient.Stock>> stock) { }

  /**
   * One quote batch plus per-SKU stock, all in parallel under one deadline (client timeout + 500 ms, inside the Gateway
   * 2 s budget); leftovers are cancelled and any failure is DependencyUnavailable. Shared by the read preview and CART-02 merge.
   */
  public Fetched fetch(List<CartRepository.CartItemRow> items) {
    var quotes = CompletableFuture.supplyAsync(() -> catalog.quote(items.stream().map(CartRepository.CartItemRow::variantId).toList()), pool);
    var stocks = new HashMap<String, CompletableFuture<Optional<StockClient.Stock>>>();
    for (var item : items) stocks.computeIfAbsent(item.sku(), sku -> CompletableFuture.supplyAsync(() -> stock.stock(sku), pool));
    var all = new java.util.ArrayList<CompletableFuture<?>>(stocks.values());
    all.add(quotes);
    try { CompletableFuture.allOf(all.toArray(CompletableFuture[]::new)).get(timeout.toMillis() + 500, TimeUnit.MILLISECONDS); }
    catch (InterruptedException e) { Thread.currentThread().interrupt(); all.forEach(f -> f.cancel(true)); throw new DependencyUnavailable("cart dependencies"); }
    catch (Exception e) { all.forEach(f -> f.cancel(true)); throw new DependencyUnavailable("cart dependencies"); }
    var observed = new HashMap<String, Optional<StockClient.Stock>>();
    stocks.forEach((sku, f) -> observed.put(sku, f.join()));
    return new Fetched(quotes.join(), observed);
  }

  public Result load(List<CartRepository.CartItemRow> items) {
    if (items.isEmpty()) return new Result(Map.of(), "EMPTY");
    try {
      var fetched = fetch(items);
      var quoted = fetched.quotes();
      var result = new HashMap<UUID, CartView.Preview>();
      for (var item : items) {
        var quote = quoted.get(item.variantId());
        var observed = fetched.stock().get(item.sku());
        boolean active = quote != null && quote.found() && quote.active();
        long price = quote != null && quote.found() ? quote.effectivePrice() : item.priceSnapshot();
        result.put(item.id(), new CartView.Preview(price, price != item.priceSnapshot(), active,
            observed.map(StockClient.Stock::available).orElse(0), observed.map(StockClient.Stock::observedAt).orElse(null)));
      }
      return new Result(result, "OK");
    } catch (RuntimeException e) {
      return new Result(Map.of(), "UNAVAILABLE");
    }
  }
}
