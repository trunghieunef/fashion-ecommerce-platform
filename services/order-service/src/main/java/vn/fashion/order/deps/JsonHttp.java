package vn.fashion.order.deps;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** JSON call to another service with a service token; network, timeout or a non-JSON body is DependencyUnavailable. */
@Component
public class JsonHttp {
  public record Reply(int status, JsonNode body) {
    public JsonNode data() { return body.path("data"); }
    public String message() { return body.path("message").asString(""); }
  }
  private final HttpClient http;
  private final ServiceTokens tokens;
  private final ObjectMapper json;
  private final Duration timeout;

  public JsonHttp(ServiceTokens tokens, ObjectMapper json, @Value("${fashion.order.dependency-timeout:2s}") Duration timeout) {
    this.tokens = tokens; this.json = json; this.timeout = timeout;
    this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
  }

  /** body null = GET; headers alternate name, value. */
  public Reply call(String dependency, String audience, String url, Object body, String... headers) {
    try {
      var request = HttpRequest.newBuilder(URI.create(url)).timeout(timeout).header("Authorization", tokens.bearer(audience))
          .header("Accept", "application/json");
      for (int i = 0; i < headers.length; i += 2) request.header(headers[i], headers[i + 1]);
      if (body == null) request.GET();
      else request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)));
      var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
      return new Reply(response.statusCode(), json.readTree(response.body()));
    } catch (DependencyUnavailable e) { throw e; }
    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new DependencyUnavailable(dependency); }
    catch (Exception e) { throw new DependencyUnavailable(dependency); }
  }
}
