package vn.fashion.catalog.media;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import vn.fashion.catalog.admin.CatalogAdminTestSupport;

import static org.assertj.core.api.Assertions.assertThat;

class MediaAttachIntegrationTest extends CatalogAdminTestSupport {
  private static final UUID OPS_B = UUID.randomUUID();

  /** Approved upload plus asset row, owned by actor and bound to one target. */
  private UUID asset(UUID actor, String type, UUID target, String state, String availability) {
    UUID id = UUID.randomUUID();
    boolean product = type.equals("PRODUCT");
    jdbc.update("insert into media_uploads(id,actor_id,target_type,product_id,collection_id,filename,content_type,size_bytes,quarantine_key,"
        + "put_expires_at,complete_deadline,state,reason_code,terminal_at) values (?,?,?,?,?,'synthetic.jpg','image/jpeg',1,?,now(),now() + interval '24 hours',?,?,now())",
        id, actor, type, product ? target : null, product ? null : target, "quarantine/" + id + "/raw", state, state.equals("REJECTED") ? "IMAGE_DECODE_FAILED" : null);
    jdbc.update("insert into media_assets(id,image_key,thumb_key,image_content_type,image_size_bytes,image_width,image_height,image_sha256,"
        + "thumb_content_type,thumb_size_bytes,thumb_width,thumb_height,thumb_sha256,availability) values (?,?,?,'image/jpeg',10,20,30,?,'image/jpeg',1,2,3,?,?)",
        id, "approved/" + id + "/image", "approved/" + id + "/thumb", "a".repeat(64), "b".repeat(64), availability);
    return id;
  }
  private UUID own(UUID product) { return asset(ACTOR, "PRODUCT", product, "APPROVED", "AVAILABLE"); }
  private UUID ownC(UUID collection) { return asset(ACTOR, "COLLECTION", collection, "APPROVED", "AVAILABLE"); }
  private Map<String,Object> item(UUID asset, int sort) {
    return new HashMap<>(Map.of("asset_id", asset, "alt_vi", "Ảnh", "alt_en", "Image", "sort_order", sort));
  }
  private Map<String,Object> citem(UUID asset, int sort) {
    return new HashMap<>(Map.of("asset_id", asset, "caption_vi", "Chú thích", "caption_en", "Caption", "sort_order", sort));
  }
  private Map<String,Object> body(long version, Object... items) { return new HashMap<>(Map.of("expected_version", version, "images", List.of(items))); }
  private HttpResponse<String> put(UUID actor, UUID product, Map<String,Object> body) {
    return send("PUT", "/admin/api/v1/catalog/products/" + product + "/images", tokenFor(actor, "catalog.write"), null, mapper.writeValueAsString(body));
  }
  private HttpResponse<String> put(UUID product, Map<String,Object> body) { return put(ACTOR, product, body); }
  private HttpResponse<String> putC(UUID c, Map<String,Object> body) {
    return send("PUT", "/admin/api/v1/catalog/collections/" + c + "/images", token("catalog.write"), null, mapper.writeValueAsString(body));
  }
  private UUID collection(String slug) {
    return UUID.fromString(data(call("POST", "/collections", Map.of("name_vi", "Bộ", "name_en", "Col", "slug", slug, "items", List.of())), 201).path("id").asText());
  }
  private void invalid(HttpResponse<String> response, String field) {
    data(response, 400);
    assertThat(json(response).path("code").asText()).isEqualTo("VALIDATION_ERROR");
    var fields = new ArrayList<String>(); json(response).path("errors").forEach(e -> fields.add(e.path("field").asText()));
    assertThat(fields).contains(field);
  }
  private UUID ready() { UUID p = createProduct("shirt"); createVariant(p, "SHIRT-M"); return p; }
  private JsonNode getImages(UUID product) { return data(call("GET", "/products/" + product + "/images", null), 200); }
  private List<String> ids(JsonNode snapshot) {
    var out = new ArrayList<String>(); snapshot.path("images").forEach(i -> out.add(i.path("asset_id").asText())); return out;
  }

  @Test void putReplacesImagesAndBumpsVersion() {
    UUID p = createProduct("p1");
    var empty = getImages(p);
    assertThat(empty.path("images").isArray()).isTrue(); assertThat(empty.path("images")).isEmpty(); assertThat(empty.path("version").asLong()).isZero();
    UUID a1 = own(p), a2 = own(p), a3 = own(p);
    var result = data(put(p, body(0, item(a2, 1), item(a1, 1), item(a3, 0))), 200);
    assertThat(result.path("version").asLong()).isEqualTo(1);
    var tied = new ArrayList<>(List.of(a1.toString(), a2.toString())); Collections.sort(tied);
    assertThat(ids(result)).containsExactly(a3.toString(), tied.get(0), tied.get(1));
    assertThat(getImages(p)).isEqualTo(result);
    var first = result.path("images").get(0);
    assertThat(first.path("url").asText()).isEqualTo("/api/v1/catalog/images/" + a3 + "?kind=image");
    assertThat(first.path("thumb_url").asText()).isEqualTo("/api/v1/catalog/images/" + a3 + "?kind=thumb");
    assertThat(first.path("image").path("width").asInt()).isEqualTo(20); assertThat(first.path("thumb").path("sha256").asText()).isEqualTo("b".repeat(64));
    assertThat(first.path("alt_vi").asText()).isEqualTo("Ảnh");
    assertThat(jdbc.queryForObject("select version from products where id=?", Long.class, p)).isEqualTo(1);
    var second = data(put(p, body(1, item(a1, 0))), 200);
    assertThat(ids(second)).containsExactly(a1.toString()); assertThat(second.path("version").asLong()).isEqualTo(2);
    assertThat(jdbc.queryForObject("select detached_at is not null from media_assets where id=?", Boolean.class, a2)).isTrue();
    assertThat(jdbc.queryForObject("select detached_at is null from media_assets where id=?", Boolean.class, a1)).isTrue();
    assertThat(call("GET", "/products/" + UUID.randomUUID() + "/images", null).statusCode()).isEqualTo(404);
  }
  @Test void staleVersionConflicts() {
    UUID p = createProduct("p2"); UUID a = own(p);
    var r = put(p, body(5, item(a, 0)));
    assertThat(r.statusCode()).isEqualTo(409); assertThat(json(r).path("code").asText()).isEqualTo("VERSION_CONFLICT");
    assertThat(jdbc.queryForObject("select count(*) from product_images", Integer.class)).isZero();
  }
  @Test void concurrentPutsSameVersionOneWins() throws Exception {
    UUID p = createProduct("p3"); UUID a = own(p), b = own(p);
    var pool = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1); var codes = new ArrayList<Integer>();
    var futures = new ArrayList<java.util.concurrent.Future<Integer>>();
    for (UUID asset : List.of(a, b)) futures.add(pool.submit(() -> { start.await(); return put(p, body(0, item(asset, 0))).statusCode(); }));
    start.countDown();
    for (var f : futures) codes.add(f.get(30, TimeUnit.SECONDS));
    pool.shutdown();
    Collections.sort(codes); assertThat(codes).containsExactly(200, 409);
    assertThat(jdbc.queryForObject("select count(*) from product_images where product_id=?", Integer.class, p)).isEqualTo(1);
    assertThat(jdbc.queryForObject("select version from products where id=?", Long.class, p)).isEqualTo(1);
  }
  @Test void newAssetMustBeOwnApprovedSameTargetWithinRetention() {
    UUID p = createProduct("p4"), other = createProduct("p4b"); UUID c = collection("c4");
    var bad = new ArrayList<UUID>();
    bad.add(asset(OPS_B, "PRODUCT", p, "APPROVED", "AVAILABLE"));
    bad.add(own(other));
    bad.add(ownC(c));
    bad.add(asset(ACTOR, "PRODUCT", p, "REJECTED", "AVAILABLE"));
    bad.add(asset(ACTOR, "PRODUCT", p, "APPROVED", "DELETING"));
    UUID old = own(p); jdbc.update("update media_assets set detached_at = now() - interval '8 days' where id=?", old); bad.add(old);
    UUID stale = own(p); jdbc.update("update media_assets set approved_at = now() - interval '8 days' where id=?", stale); bad.add(stale);
    bad.add(UUID.randomUUID());
    for (UUID id : bad) invalid(put(p, body(0, item(id, 0))), "images[0].asset_id");
    assertThat(jdbc.queryForObject("select count(*) from product_images", Integer.class)).isZero();
    data(put(p, body(0, item(own(p), 0))), 200);
  }
  @Test void retainedAssetOfOtherActorIsKept() {
    UUID p = createProduct("p5"); UUID a = attachSyntheticImage(p);
    var change = item(a, 3); change.put("alt_vi", "Mới");
    var result = data(put(OPS_B, p, body(0, change)), 200);
    assertThat(result.path("images").get(0).path("alt_vi").asText()).isEqualTo("Mới");
    assertThat(jdbc.queryForObject("select actor_id from media_uploads where id=?", UUID.class, a)).isEqualTo(ACTOR);
    invalid(put(OPS_B, p, body(1, item(a, 0), item(asset(ACTOR, "PRODUCT", p, "APPROVED", "AVAILABLE"), 1))), "images[1].asset_id");
  }
  @Test void reattachWithinRetentionByUploader() {
    UUID p = createProduct("p6"); UUID a = own(p);
    data(put(p, body(0, item(a, 0))), 200); data(put(p, body(1)), 200);
    jdbc.update("update media_assets set detached_at = now() - interval '6 days' where id=?", a);
    invalid(put(OPS_B, p, body(2, item(a, 0))), "images[0].asset_id");
    data(put(p, body(2, item(a, 0))), 200);
    assertThat(jdbc.queryForObject("select detached_at is null from media_assets where id=?", Boolean.class, a)).isTrue();
  }
  @Test void duplicatesLimitsAndTextBounds() {
    UUID p = createProduct("p7"); UUID a = own(p), b = own(p);
    invalid(put(p, body(0, item(a, 0), item(a, 1))), "images[1].asset_id");
    var many = new ArrayList<Object>(); for (int i = 0; i < 21; i++) many.add(item(UUID.randomUUID(), i));
    invalid(put(p, new HashMap<>(Map.of("expected_version", 0, "images", many))), "images");
    UUID c = collection("c7"); var wide = new ArrayList<Object>(); for (int i = 0; i < 51; i++) wide.add(citem(UUID.randomUUID(), i));
    var wideBody = new HashMap<String,Object>(Map.of("expected_version", 0, "images", wide)); wideBody.put("cover_asset_id", null);
    invalid(putC(c, wideBody), "images");
    var tooLong = item(a, 0); tooLong.put("alt_vi", "😀".repeat(256));
    invalid(put(p, body(0, tooLong)), "images[0].alt_vi");
    var blank = item(a, 0); blank.put("alt_en", "   ");
    invalid(put(p, body(0, blank)), "images[0].alt_en");
    var exact = item(a, 0); exact.put("alt_vi", "😀".repeat(255));
    data(put(p, body(0, exact)), 200);
    var sort = item(b, 0); sort.put("sort_order", -1);
    invalid(put(p, body(1, sort)), "images[0].sort_order");
    sort.put("sort_order", 1.5);
    invalid(put(p, body(1, sort)), "images[0].sort_order");
    UUID k = ownC(c); var cap = citem(k, 0); cap.put("caption_vi", "x".repeat(501));
    var req = new HashMap<String,Object>(Map.of("expected_version", 0, "images", List.of(cap))); req.put("cover_asset_id", null);
    invalid(putC(c, req), "images[0].caption_vi");
  }
  @Test void variantColorMustMatchVariantOfSameProduct() {
    UUID p = createProduct("p8"); createVariant(p, "V1"); UUID a = own(p);
    var item = item(a, 0); item.put("variant_color", "blue");
    invalid(put(p, body(1, item)), "images[0].variant_color");
    item.put("variant_color", "Blue");
    assertThat(data(put(p, body(1, item)), 200).path("images").get(0).path("variant_color").asText()).isEqualTo("Blue");
  }
  @Test void coverMustBeInImagesAndNullAllowed() {
    UUID c = collection("c9"); UUID a = ownC(c), b = ownC(c);
    var outside = new HashMap<String,Object>(body(0, citem(a, 0))); outside.put("cover_asset_id", b);
    invalid(putC(c, outside), "cover_asset_id");
    invalid(putC(c, body(0, citem(a, 0))), "cover_asset_id");
    var nul = new HashMap<String,Object>(body(0, citem(a, 0))); nul.put("cover_asset_id", null);
    var ok = data(putC(c, nul), 200);
    assertThat(ok.path("version").asLong()).isEqualTo(1); assertThat(ok.path("cover_asset_id").isNull()).isTrue();
    assertThat(ok.path("images").get(0).path("caption_vi").asText()).isEqualTo("Chú thích");
    var good = new HashMap<String,Object>(body(1, citem(a, 0), citem(b, 1))); good.put("cover_asset_id", b);
    assertThat(data(putC(c, good), 200).path("cover_asset_id").asText()).isEqualTo(b.toString());
    assertThat(data(call("GET", "/collections/" + c + "/images", null), 200).path("cover_asset_id").asText()).isEqualTo(b.toString());
    var drop = new HashMap<String,Object>(body(2, citem(a, 0))); drop.put("cover_asset_id", null);
    data(putC(c, drop), 200);
  }
  @Test void unknownNestedFieldReportsPath() {
    UUID p = createProduct("p10"); UUID a = own(p);
    var item = item(a, 0); item.put("url", "https://x");
    invalid(put(p, body(0, item)), "images[0].url");
    var top = body(0); top.put("extra", 1);
    invalid(put(p, top), "extra");
    var missing = new HashMap<String,Object>(); missing.put("images", List.of());
    invalid(put(p, missing), "expected_version");
  }
  @Test void activeProductCannotDropLastImage() {
    UUID p = ready(); attachSyntheticImage(p);
    data(call("POST", "/products/" + p + "/publish", Map.of("expected_version", 1)), 200);
    var r = put(p, body(2));
    invalid(r, "images"); assertThat(json(r).path("message").asText()).isEqualTo("ACTIVE_PRODUCT_REQUIRES_IMAGE");
    data(call("POST", "/products/" + p + "/unpublish", Map.of("expected_version", 2)), 200);
    assertThat(ids(data(put(p, body(3)), 200))).isEmpty();
  }
  @Test void publishRequiresImage() {
    UUID p = ready();
    invalid(call("POST", "/products/" + p + "/publish", Map.of("expected_version", 1)), "images");
    attachSyntheticImage(p);
    data(call("POST", "/products/" + p + "/publish", Map.of("expected_version", 1)), 200);
  }
  @Test void legacyActiveProductWithoutImageKeepsStatus() {
    UUID p = ready(); jdbc.update("update products set status='ACTIVE' where id=?", p);
    assertThat(getImages(p).path("images")).isEmpty();
    UUID a = own(p);
    data(put(p, body(1, item(a, 0))), 200);
    assertThat(jdbc.queryForObject("select status from products where id=?", String.class, p)).isEqualTo("ACTIVE");
  }
  @Test void auditDiffContainsOnlyIds() {
    UUID p = createProduct("p14"); UUID a = own(p), b = own(p);
    data(put(p, body(0, item(a, 0))), 200); data(put(p, body(1, item(b, 0))), 200);
    var after = jdbc.queryForObject("select after_data::text from audit_logs where action='catalog.product.images.update' order by created_at desc limit 1", String.class);
    assertThat(after).contains(a.toString(), b.toString()).doesNotContain("Ảnh", "alt", "caption", "key", "url", "http", "approved/", "quarantine/");
    var node = mapper.readTree(after);
    assertThat(node.path("added").get(0).asText()).isEqualTo(b.toString()); assertThat(node.path("removed").get(0).asText()).isEqualTo(a.toString());
    assertThat(node.has("cover_before")).isTrue();
    assertThat(jdbc.queryForObject("select count(*) from audit_logs where action='catalog.product.images.update'", Integer.class)).isEqualTo(2);
  }
  @Test void collectionCoverUrlComesFromCover() {
    UUID c = collection("c15"); UUID a = ownC(c);
    assertThat(data(call("GET", "/collections/" + c, null), 200).path("cover_url").isNull()).isTrue();
    var req = new HashMap<String,Object>(body(0, citem(a, 0))); req.put("cover_asset_id", a);
    data(putC(c, req), 200);
    assertThat(data(call("GET", "/collections/" + c, null), 200).path("cover_url").asText()).isEqualTo("/api/v1/catalog/images/" + a + "?kind=image");
  }
}
