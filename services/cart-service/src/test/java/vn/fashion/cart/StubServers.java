package vn.fashion.cart;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Function;

/** JDK HTTP stub for catalog quote / inventory stock: scripted responses, latency and request capture. */
public final class StubServers {
  public record Reply(int status, String body) { }
  public record Seen(String path, String authorization, String body) { }

  public static final class Stub {
    final HttpServer server;
    volatile Function<Seen, Reply> handler = seen -> new Reply(500, "{}");
    volatile long delayMillis;
    public final List<Seen> seen = new CopyOnWriteArrayList<>();
    Stub() {
      try { server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 256); }
      catch (IOException e) { throw new AssertionError(e); }
      server.setExecutor(Executors.newCachedThreadPool());
      server.createContext("/", this::handle);
      server.start();
    }
    void handle(HttpExchange exchange) throws IOException {
      var request = new Seen(exchange.getRequestURI().getPath(), exchange.getRequestHeaders().getFirst("Authorization"),
          new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      seen.add(request);
      try { if (delayMillis > 0) Thread.sleep(delayMillis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
      var reply = handler.apply(request);
      byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(reply.status(), body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    }
    public String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    public void reply(Function<Seen, Reply> handler) { this.handler = handler; }
    public void delay(long millis) { this.delayMillis = millis; }
    public void reset() { seen.clear(); delayMillis = 0; handler = s -> new Reply(500, "{}"); }
  }

  public static final Stub CATALOG = new Stub();
  public static final Stub INVENTORY = new Stub();
  private StubServers() { }

  public static String quoteItem(String variantId, boolean active, String sku, long price, long version) {
    return "{\"variant_id\":\"" + variantId + "\",\"found\":true,\"active\":" + active + ",\"sku\":\"" + sku
        + "\",\"effective_price\":" + price + ",\"product_version\":" + version + "}";
  }
  public static String missing(String variantId) { return "{\"variant_id\":\"" + variantId + "\",\"found\":false}"; }
  public static String ok(String data) { return "{\"code\":\"OK\",\"data\":" + data + ",\"metadata\":{\"request_id\":\"r\",\"trace_id\":\"t\"}}"; }
  public static String stock(String sku, int available) {
    return ok("{\"sku\":\"" + sku + "\",\"variant_id\":\"00000000-0000-4000-8000-000000000000\",\"available\":" + available
        + ",\"version\":1,\"observed_at\":\"2026-10-18T03:00:00Z\"}");
  }
}
