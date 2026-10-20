package vn.fashion.order;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Function;

/** JDK HTTP stub for cart snapshot / catalog quote / shipping quote: scripted responses, latency and request capture. */
public final class StubServers {
  public record Reply(int status, String body) { }
  public record Seen(String path, String query, String authorization, String cartActor, String idempotencyKey, String body) { }

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
      var headers = exchange.getRequestHeaders();
      var request = new Seen(exchange.getRequestURI().getPath(), exchange.getRequestURI().getQuery(), headers.getFirst("Authorization"),
          headers.getFirst("Cart-Actor"), headers.getFirst("Idempotency-Key"),
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

  public static final Stub CART = new Stub();
  public static final Stub CATALOG = new Stub();
  public static final Stub SHIPPING = new Stub();
  public static final Stub INVENTORY = new Stub();
  public static final Stub PAYMENT = new Stub();
  private StubServers() { }

  public static String ok(String data) { return "{\"code\":\"OK\",\"data\":" + data + ",\"metadata\":{\"request_id\":\"r\",\"trace_id\":\"t\"}}"; }
  public static String error(String code, String message) {
    return "{\"code\":\"" + code + "\",\"message\":\"" + message + "\",\"metadata\":{\"request_id\":\"r\",\"trace_id\":\"t\"}}";
  }
}
