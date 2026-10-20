package vn.fashion.order.deps;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.MissingNode;

/**
 * A saga call result (06 section 1.2): OK, REJECTED (a business answer that will not change on retry, code = message) or
 * UNKNOWN (timeout, 5xx, auth/config failure) which is retried with the same business key.
 */
public record Outcome(Result result, String code, JsonNode data) {
  public enum Result { OK, REJECTED, UNKNOWN }
  public static Outcome ok(JsonNode data) { return new Outcome(Result.OK, null, data); }
  public static Outcome rejected(String code) { return new Outcome(Result.REJECTED, code, MissingNode.getInstance()); }
  public static Outcome unknown(String code) { return new Outcome(Result.UNKNOWN, code, MissingNode.getInstance()); }

  /** 2xx OK; 400/404/409/422 REJECTED with the message; anything else (401/403 config, 5xx) UNKNOWN. */
  static Outcome of(String dependency, JsonHttp http, String audience, String url, Object body, String... headers) {
    JsonHttp.Reply reply;
    try { reply = http.call(dependency, audience, url, body, headers); }
    catch (DependencyUnavailable e) { return unknown(dependency + "_UNAVAILABLE"); }
    int s = reply.status();
    if (s >= 200 && s < 300) return ok(reply.data());
    if (s == 400 || s == 404 || s == 409 || s == 422) return rejected(reply.message());
    return unknown(dependency + "_" + s);
  }
}
