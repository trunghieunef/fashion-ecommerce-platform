package vn.fashion.catalog.media;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class UploadIntentIntegrationTest extends MediaTestSupport {
  private static final String UPLOADS = "/admin/api/v1/catalog/images/uploads";

  private Map<String, Object> body(UUID product) {
    return new HashMap<>(Map.of("filename", "a.png", "content_type", "image/png", "size_bytes", 100,
        "target_type", "PRODUCT", "target_id", product.toString()));
  }
  private HttpResponse<String> create(String key, Map<String, Object> body) {
    return send("POST", UPLOADS, token("catalog.write"), key, mapper.writeValueAsString(body));
  }
  private HttpResponse<String> status(String token, Object id) { return send("GET", UPLOADS + "/" + id, token, null, null); }

  @Test void createReturnsSignedPutAndStoresNoCapability() {
    var product = createProduct("up-1");
    var d = data(create("k1", body(product)), 201);
    assertThat(d.path("put_url").asText()).contains("X-Amz-Signature");
    assertThat(d.path("put_headers").path("Content-Type").asText()).isEqualTo("image/png");
    assertThat(d.path("put_headers").path("Content-Length").asInt()).isEqualTo(100);
    assertThat(d.path("upload_id").asText()).isNotBlank();
    for (String stored : List.of(
        jdbc.queryForObject("select response_body::text from idempotency_requests where key='k1'", String.class),
        jdbc.queryForObject("select coalesce(string_agg(after_data::text,''),'') from audit_logs where action='catalog.media.upload.create'", String.class)))
      assertThat(stored).doesNotContain("X-Amz-Signature").doesNotContain("quarantine/").doesNotContain("put_url");
    assertThat(jdbc.queryForObject("select count(*) from audit_logs where action='catalog.media.upload.create' and resource_type='media_upload'", Integer.class)).isEqualTo(1);
  }

  @Test void putUrlUploadsToQuarantine() {
    var product = createProduct("up-2");
    byte[] png = png(4, 4);
    var b = body(product); b.put("size_bytes", png.length);
    var d = data(create("k2", b), 201);
    int code = putSigned(URI.create(d.path("put_url").asText()),
        Map.of("Content-Type", "image/png", "Content-Length", String.valueOf(png.length)), png);
    assertThat(code).isEqualTo(200);
    var s = data(status(token("catalog.write"), d.path("upload_id").asText()), 200);
    assertThat(s.path("state").asText()).isEqualTo("PENDING");
    assertThat(s.path("target_type").asText()).isEqualTo("PRODUCT");
    assertThat(s.path("target_id").asText()).isEqualTo(product.toString());
    assertThat(s.path("asset").isNull()).isTrue();
    assertThat(s.path("lease_until").isNull()).isTrue();
  }

  @Test void replaySameKeyReturnsSameUploadWithRemainingTtl() {
    var b = body(createProduct("up-3"));
    var first = data(create("k3", b), 201);
    var second = data(create("k3", b), 201);
    assertThat(second.path("upload_id").asText()).isEqualTo(first.path("upload_id").asText());
    assertThat(second.path("put_expires_at").asText()).isEqualTo(first.path("put_expires_at").asText());
    assertThat(second.path("put_url").asText()).contains("X-Amz-Signature");
    assertThat(jdbc.queryForObject("select count(*) from audit_logs where action='catalog.media.upload.create'", Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from media_uploads", Integer.class)).isEqualTo(1);
  }

  @Test void replayAfterPutExpiryIsUploadUrlExpired() {
    var b = body(createProduct("up-4"));
    var first = data(create("k4", b), 201);
    jdbc.update("update media_uploads set put_expires_at = now() - interval '1 second', complete_deadline = now() - interval '1 second' + interval '24 hours'");
    var res = create("k4", b);
    assertThat(res.statusCode()).isEqualTo(409);
    assertThat(json(res).path("code").asText()).isEqualTo("CONFLICT");
    assertThat(json(res).path("message").asText()).isEqualTo("UPLOAD_URL_EXPIRED");
    assertThat(res.body()).doesNotContain("X-Amz-Signature");
    assertThat(data(status(token("catalog.write"), first.path("upload_id").asText()), 200).path("state").asText()).isEqualTo("PENDING");
  }

  @Test void sameKeyDifferentBodyConflicts() {
    var product = createProduct("up-5");
    data(create("k5", body(product)), 201);
    var other = body(product); other.put("size_bytes", 101);
    var res = create("k5", other);
    assertThat(res.statusCode()).isEqualTo(409);
    assertThat(json(res).path("message").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
  }

  @Test void validationErrorsUseFieldPaths() {
    var product = createProduct("up-6");
    check(product, "content_type", "image/gif", "content_type");
    check(product, "size_bytes", 0, "size_bytes");
    check(product, "size_bytes", 5242881, "size_bytes");
    check(product, "size_bytes", "10", "size_bytes");
    check(product, "size_bytes", 1.5, "size_bytes");
    check(product, "target_type", "X", "target_type");
    check(product, "filename", "   ", "filename");
    check(product, "target_id", "nope", "target_id");
    check(product, "bucket", "b", "bucket");
    var missing = body(product); missing.remove("content_type");
    assertThat(fields(create("kv", missing))).contains("content_type");
    assertThat(jdbc.queryForObject("select count(*) from media_uploads", Integer.class)).isZero();
  }
  private void check(UUID product, String field, Object value, String expected) {
    var b = body(product); b.put(field, value);
    var res = create("kv-" + UUID.randomUUID(), b);
    assertThat(res.statusCode()).withFailMessage(field + "=" + value + " -> " + res.body()).isEqualTo(400);
    assertThat(fields(res)).withFailMessage(res.body()).contains(expected);
  }
  private List<String> fields(HttpResponse<String> res) {
    var out = new java.util.ArrayList<String>();
    for (JsonNode e : json(res).path("errors")) out.add(e.path("field").asText());
    return out;
  }

  @Test void unknownTargetIs404() {
    var b = body(UUID.randomUUID());
    assertThat(create("k7", b).statusCode()).isEqualTo(404);
    b.put("target_type", "COLLECTION");
    assertThat(create("k8", b).statusCode()).isEqualTo(404);
    assertThat(jdbc.queryForObject("select count(*) from media_uploads", Integer.class)).isZero();
  }

  @Test void filenameNeverReachesObjectKey() {
    var b = body(createProduct("up-8")); b.put("filename", "../../x.png");
    var d = data(create("k9", b), 201);
    var id = d.path("upload_id").asText();
    assertThat(jdbc.queryForObject("select quarantine_key from media_uploads where id=?::uuid", String.class, id))
        .isEqualTo("quarantine/" + id + "/raw");
    assertThat(d.path("put_url").asText()).doesNotContain("x.png");
  }

  @Test void statusIsOwnerOnly() {
    var d = data(create("k10", body(createProduct("up-9"))), 201);
    String id = d.path("upload_id").asText();
    assertThat(status(token("catalog.write"), id).statusCode()).isEqualTo(200);
    assertThat(status(tokenFor(UUID.randomUUID(), "catalog.write"), id).statusCode()).isEqualTo(404);
    assertThat(status(token("catalog.write"), UUID.randomUUID()).statusCode()).isEqualTo(404);
    assertThat(status(null, id).statusCode()).isEqualTo(401);
    assertThat(status(token(), id).statusCode()).isEqualTo(403);
  }
}
