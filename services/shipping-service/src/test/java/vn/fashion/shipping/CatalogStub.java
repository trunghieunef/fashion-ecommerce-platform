package vn.fashion.shipping;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/** JDK HTTP stub of catalog internal quote: weight/active per variant id, unknown ids are found=false; status scriptable. */
public final class CatalogStub {
  public record Variant(int weightGrams, boolean active) { }
  public static final Map<String, Variant> VARIANTS = new ConcurrentHashMap<>();
  public static final List<String> AUTH = new CopyOnWriteArrayList<>();
  public static volatile int status = 200;
  /** When set, sent as-is instead of the computed answer. */
  public static volatile String answer;
  private static final Pattern UUID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  private static final HttpServer SERVER = start();
  private CatalogStub() { }

  private static HttpServer start() {
    try {
      var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 64);
      server.setExecutor(Executors.newCachedThreadPool());
      server.createContext("/", exchange -> {
        AUTH.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        var items = UUID.matcher(body).results().map(m -> {
          var v = VARIANTS.get(m.group());
          return v == null ? "{\"variant_id\":\"" + m.group() + "\",\"found\":false}"
              : "{\"variant_id\":\"" + m.group() + "\",\"found\":true,\"active\":" + v.active() + ",\"weight_grams\":" + v.weightGrams() + "}";
        }).toList();
        byte[] out = (answer != null ? answer : status == 200 ? "{\"code\":\"OK\",\"data\":[" + String.join(",", items) + "]}" : "{}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, out.length);
        exchange.getResponseBody().write(out);
        exchange.close();
      });
      server.start();
      return server;
    } catch (IOException e) { throw new AssertionError(e); }
  }
  public static String url() { return "http://127.0.0.1:" + SERVER.getAddress().getPort(); }
  public static void reset() { VARIANTS.clear(); AUTH.clear(); status = 200; answer = null; }
}
