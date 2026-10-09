package vn.fashion.catalog.media;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class MediaReadIntegrationTest extends MediaTestSupport {
  private static final byte[] IMAGE = "image-bytes".getBytes();
  private static final byte[] THUMB = "thumb".getBytes();
  private static final String SHA_IMAGE = "a".repeat(64), SHA_THUMB = "b".repeat(64);
  @MockitoSpyBean MediaStorage storage;
  @Autowired MediaStorage rawStorage;
  private final HttpClient http = HttpClient.newHttpClient();

  /** Asset with real S3 objects, optionally attached to the product (uploaded by actor). */
  private UUID asset(UUID actor, UUID product, boolean attach) {
    UUID id = UUID.randomUUID();
    jdbc.update("insert into media_uploads(id,actor_id,target_type,product_id,filename,content_type,size_bytes,quarantine_key,"
        + "put_expires_at,complete_deadline,state,terminal_at) values (?,?,'PRODUCT',?,'synthetic.jpg','image/jpeg',1,?,now(),now() + interval '24 hours','APPROVED',now())",
        id, actor, product, "quarantine/" + id + "/raw");
    jdbc.update("insert into media_assets(id,image_key,thumb_key,image_content_type,image_size_bytes,image_width,image_height,image_sha256,"
        + "thumb_content_type,thumb_size_bytes,thumb_width,thumb_height,thumb_sha256) values (?,?,?,'image/png',?,2,2,?,'image/jpeg',?,1,1,?)",
        id, "approved/" + id + "/image", "approved/" + id + "/thumb", IMAGE.length, SHA_IMAGE, THUMB.length, SHA_THUMB);
    rawStorage.putIfAbsent("approved/" + id + "/image", IMAGE, "image/png");
    rawStorage.putIfAbsent("approved/" + id + "/thumb", THUMB, "image/jpeg");
    if (attach) jdbc.update("insert into product_images(product_id,asset_id,alt_vi,alt_en,sort_order) values (?,?,'Ảnh','Image',0)", product, id);
    return id;
  }
  private UUID activeProduct() {
    UUID p = createProduct("p-" + UUID.randomUUID());
    jdbc.update("update products set status='ACTIVE' where id=?", p);
    return p;
  }
  private HttpResponse<byte[]> get(String path, String token, String... headers) {
    try {
      var r = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
      if (token != null) r.header("Authorization", "Bearer " + token);
      for (int i = 0; i < headers.length; i += 2) r.header(headers[i], headers[i + 1]);
      return http.send(r.GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    } catch (Exception e) { throw new AssertionError(e); }
  }
  private HttpResponse<byte[]> pub(UUID id, String kind, String... headers) {
    return get("/api/v1/catalog/images/" + id + (kind == null ? "" : "?kind=" + kind), null, headers);
  }
  private HttpResponse<byte[]> admin(UUID id, String kind, UUID actor) {
    return get("/admin/api/v1/catalog/images/" + id + (kind == null ? "" : "?kind=" + kind), tokenFor(actor, "catalog.write"));
  }
  private void problem(HttpResponse<byte[]> r, int status) {
    assertThat(r.statusCode()).isEqualTo(status);
    assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
    assertThat(r.headers().firstValue("X-Correlation-Id")).isPresent();
    assertThat(r.headers().firstValue("Cache-Control")).contains("no-store");
  }
  private String etag(HttpResponse<byte[]> r) { return r.headers().firstValue("ETag").orElseThrow(); }

  private void attachLookbook(UUID col, UUID assetId) {
    // lookbook FK binds asset to its upload's collection, so retarget the synthetic upload
    jdbc.update("update media_uploads set target_type='COLLECTION', product_id=null, collection_id=? where id=?", col, assetId);
    jdbc.update("insert into lookbook_images(collection_id,asset_id,sort_order) values (?,?,0)", col, assetId);
  }
  private UUID collection(String slug, String start, String end, UUID product) {
    UUID col = UUID.randomUUID();
    jdbc.update("insert into collections(id,name_vi,name_en,slug,status,start_at,end_at) values (?,'B','C',?,'ACTIVE',"
        + start + "," + end + ")", col, slug);
    jdbc.update("insert into collection_items(collection_id,product_id) values (?,?)", col, product);
    return col;
  }

  @Test void publicServesActiveProductImageWithCacheHeaders() {
    UUID a = asset(ACTOR, activeProduct(), true);
    var r = pub(a, null);
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.body()).isEqualTo(IMAGE);
    assertThat(r.headers().firstValue("Content-Type")).contains("image/png");
    assertThat(r.headers().firstValue("Content-Length")).contains(String.valueOf(IMAGE.length));
    assertThat(r.headers().firstValue("Cache-Control")).contains("public, max-age=300");
    assertThat(etag(r)).isEqualTo("\"" + SHA_IMAGE + "\"");
    assertThat(r.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
    assertThat(r.headers().firstValue("Content-Disposition")).contains("inline");
    assertThat(r.headers().firstValue("X-Correlation-Id")).isPresent();
  }

  @Test void thumbHasOwnValidator() {
    UUID a = asset(ACTOR, activeProduct(), true);
    var t = pub(a, "thumb");
    assertThat(t.statusCode()).isEqualTo(200);
    assertThat(t.body()).isEqualTo(THUMB);
    assertThat(t.headers().firstValue("Content-Type")).contains("image/jpeg");
    assertThat(etag(t)).isEqualTo("\"" + SHA_THUMB + "\"").isNotEqualTo(etag(pub(a, "image")));
  }

  @Test void conditionalGetReturns304WithoutStorage() {
    UUID a = asset(ACTOR, activeProduct(), true);
    clearInvocations(storage);
    String e = "\"" + SHA_IMAGE + "\"";
    for (String h : List.of(e, "\"x\", " + e, "W/" + e, "*")) {
      var r = pub(a, "image", "If-None-Match", h);
      assertThat(r.statusCode()).as(h).isEqualTo(304);
      assertThat(r.body()).isEmpty();
      assertThat(r.headers().firstValue("ETag")).contains(e);
    }
    verify(storage, never()).open(anyString());
    assertThat(pub(a, "image", "If-None-Match", "\"other\"").statusCode()).isEqualTo(200);
  }

  @Test void notVisibleIs404EvenWithMatchingEtag() {
    String e = "\"" + SHA_IMAGE + "\"";
    UUID draft = asset(ACTOR, createProduct("draft"), true);
    problem(pub(draft, "image", "If-None-Match", e), 404);
    UUID p = activeProduct();
    UUID a = asset(ACTOR, p, true);
    assertThat(pub(a, null).statusCode()).isEqualTo(200);
    jdbc.update("update products set status='INACTIVE' where id=?", p);
    problem(pub(a, "image", "If-None-Match", e), 404);
    // lookbook outside its window, then inside it but without any ACTIVE product
    UUID prod = activeProduct();
    UUID cAsset = asset(ACTOR, prod, false);
    UUID col = collection("c-out", "now() + interval '1 day'", "now() + interval '2 day'", prod);
    attachLookbook(col, cAsset);
    problem(pub(cAsset, "image", "If-None-Match", e), 404);
    jdbc.update("update collections set start_at=null, end_at=null where id=?", col);
    assertThat(pub(cAsset, null).statusCode()).isEqualTo(200);
    jdbc.update("update products set status='DRAFT' where id=?", prod);
    problem(pub(cAsset, "image", "If-None-Match", e), 404);
  }

  @Test void collectionLookbookVisibleInWindow() {
    UUID prod = activeProduct();
    UUID a = asset(ACTOR, prod, false);
    attachLookbook(collection("c-in", "now() - interval '1 day'", "now() + interval '1 day'", prod), a);
    var r = pub(a, "image");
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.body()).isEqualTo(IMAGE);
  }

  @Test void invalidKindIs400() {
    UUID a = asset(ACTOR, activeProduct(), true);
    for (String kind : List.of("raw", "")) {
      for (var resp : List.of(pub(a, kind), admin(a, kind, ACTOR))) {
        problem(resp, 400);
        assertThat(new String(resp.body())).contains("\"field\":\"kind\"");
      }
    }
    problem(pub(UUID.randomUUID(), "raw"), 400); // rejected before the asset lookup
  }

  @Test void missingObjectIs503() {
    UUID a = asset(ACTOR, activeProduct(), true);
    rawStorage.delete("approved/" + a + "/image");
    var r = pub(a, "image");
    problem(r, 503);
    assertThat(new String(r.body())).contains("DEPENDENCY_UNAVAILABLE");
    assertThat(pub(a, "thumb").statusCode()).isEqualTo(200);
  }

  @Test void browserAcceptImageStillGetsJsonErrors() {
    problem(pub(UUID.randomUUID(), null, "Accept", "image/*"), 404);
    problem(pub(UUID.randomUUID(), "raw", "Accept", "image/*"), 400);
    UUID a = asset(ACTOR, activeProduct(), true);
    rawStorage.delete("approved/" + a + "/image");
    problem(pub(a, null, "Accept", "image/*"), 503);
    assertThat(pub(a, "thumb", "Accept", "image/*").statusCode()).isEqualTo(200);
  }

  @Test void invalidUuidWithImageAcceptIs400Json() {
    var r = get("/api/v1/catalog/images/not-a-uuid", null, "Accept", "image/*");
    problem(r, 400);
    assertThat(new String(r.body())).contains("INVALID_PARAMETER");
  }

  @Test void adminPreviewDraftNoStore() {
    UUID a = asset(ACTOR, createProduct("draft"), true);
    var r = admin(a, "image", ACTOR);
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.body()).isEqualTo(IMAGE);
    assertThat(r.headers().firstValue("Cache-Control")).contains("private, no-store");
    assertThat(r.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
    assertThat(admin(a, "thumb", UUID.randomUUID()).statusCode()).isEqualTo(200); // attached: any OPS
    // admin preview never uses the public 304 contract
    for (String h : List.of("\"" + SHA_IMAGE + "\"", "*")) {
      var m = get("/admin/api/v1/catalog/images/" + a + "?kind=image", tokenFor(ACTOR, "catalog.write"), "If-None-Match", h);
      assertThat(m.statusCode()).as(h).isEqualTo(200);
      assertThat(m.body()).isEqualTo(IMAGE);
      assertThat(m.headers().firstValue("Cache-Control")).contains("private, no-store");
    }
    assertThat(get("/admin/api/v1/catalog/images/" + a, null).statusCode()).isEqualTo(401);
    assertThat(get("/admin/api/v1/catalog/images/" + a, token("orders.read")).statusCode()).isEqualTo(403);
  }

  @Test void adminPreviewDetachedOnlyUploader() {
    UUID other = UUID.randomUUID();
    UUID unattached = asset(ACTOR, createProduct("p1"), false);
    assertThat(admin(unattached, "image", ACTOR).statusCode()).isEqualTo(200);
    problem(admin(unattached, "image", other), 404);
    UUID detached = asset(ACTOR, createProduct("p2"), true);
    jdbc.update("delete from product_images where asset_id=?", detached);
    jdbc.update("update media_assets set detached_at=now() where id=?", detached);
    assertThat(admin(detached, "image", ACTOR).statusCode()).isEqualTo(200);
    problem(admin(detached, "image", other), 404);
  }

  @Test void deletingAssetIs404() {
    UUID a = asset(ACTOR, activeProduct(), true);
    jdbc.update("update media_assets set availability='DELETING' where id=?", a);
    problem(pub(a, null), 404);
    problem(admin(a, null, ACTOR), 404);
    problem(pub(UUID.randomUUID(), null), 404);
  }
}
