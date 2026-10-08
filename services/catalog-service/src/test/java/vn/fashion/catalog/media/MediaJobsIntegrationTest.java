package vn.fashion.catalog.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

class MediaJobsIntegrationTest extends MediaTestSupport {
  @MockitoSpyBean MediaStorage storage;
  @Autowired MediaJobs jobs;

  private static String raw(UUID id) { return "quarantine/" + id + "/raw"; }
  private static String image(UUID id) { return "approved/" + id + "/image"; }
  private static String thumb(UUID id) { return "approved/" + id + "/thumb"; }

  /** Upload row; putExpiredAgo is an SQL interval (time since put_expires_at), terminalAgo null = not terminal. */
  private UUID upload(UUID product, String state, String putExpiredAgo, String terminalAgo) {
    UUID id = UUID.randomUUID();
    boolean processing = state.equals("PROCESSING");
    jdbc.update("insert into media_uploads(id,actor_id,target_type,product_id,filename,content_type,size_bytes,quarantine_key,put_expires_at,"
        + "complete_deadline,state,reason_code,terminal_at,lease_token,lease_until) values (?,?,'PRODUCT',?,'synthetic.jpg','image/jpeg',1,?,"
        + "now() - ?::interval, now() - ?::interval + interval '24 hours',?,?,now() - ?::interval,?,?)",
        id, ACTOR, product, raw(id), putExpiredAgo, putExpiredAgo, state, state.equals("REJECTED") ? "IMAGE_DECODE_FAILED" : null,
        terminalAgo, processing ? UUID.randomUUID() : null, processing ? java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(60)) : null);
    return id;
  }
  private UUID asset(UUID product) {
    UUID id = upload(product, "APPROVED", "1 hour", "1 hour");
    jdbc.update("update media_uploads set quarantine_cleaned_at=now() where id=?", id);
    jdbc.update("insert into media_assets(id,image_key,thumb_key,image_content_type,image_size_bytes,image_width,image_height,image_sha256,"
        + "thumb_content_type,thumb_size_bytes,thumb_width,thumb_height,thumb_sha256) values (?,?,?,'image/jpeg',1,1,1,?,'image/jpeg',1,1,1,?)",
        id, image(id), thumb(id), "0".repeat(64), "0".repeat(64));
    storage.putIfAbsent(image(id), new byte[] {1}, "image/jpeg");
    storage.putIfAbsent(thumb(id), new byte[] {1}, "image/jpeg");
    return id;
  }
  private Map<String, Object> row(UUID id) { return jdbc.queryForMap("select * from media_uploads where id=?", id); }
  private String availability(UUID id) { return jdbc.queryForObject("select availability from media_assets where id=?", String.class, id); }
  private long deletes(String key) {
    return mockingDetails(storage).getInvocations().stream()
        .filter(i -> i.getMethod().getName().equals("delete") && key.equals(i.getArgument(0))).count();
  }

  @Test void sweepExpiresStaleUploadAndDeletesRaw() {
    UUID p = createProduct("s1");
    UUID stale = upload(p, "PENDING", "25 hours", null), fresh = upload(p, "PENDING", "23 hours", null);
    storage.putIfAbsent(raw(stale), new byte[] {1}, "image/jpeg");
    assertThat(jobs.sweepQuarantine()).isEqualTo(1);
    var r = row(stale);
    assertThat(r.get("state")).isEqualTo("EXPIRED");
    assertThat(r.get("terminal_at")).isNotNull();
    assertThat(r.get("quarantine_cleaned_at")).isNotNull();
    assertThat(r.get("quarantine_lease_token")).isNull();
    assertThat(storage.head(raw(stale))).isEmpty();
    assertThat(row(fresh).get("state")).isEqualTo("PENDING");
    assertThat(row(fresh).get("quarantine_cleaned_at")).isNull();
  }
  @Test void sweepSkipsProcessingWithLiveLease() {
    UUID p = createProduct("s2");
    UUID live = upload(p, "PROCESSING", "25 hours", null), dead = upload(p, "PROCESSING", "25 hours", null);
    jdbc.update("update media_uploads set lease_until=now() - interval '1 second' where id=?", dead);
    UUID claimed = upload(p, "REJECTED", "1 hour", "1 hour"), takeover = upload(p, "REJECTED", "1 hour", "1 hour");
    jdbc.update("update media_uploads set quarantine_lease_token=gen_random_uuid(), quarantine_lease_until=now() + interval '1 minute' where id=?", claimed);
    jdbc.update("update media_uploads set quarantine_lease_token=gen_random_uuid(), quarantine_lease_until=now() - interval '1 second' where id=?", takeover);
    assertThat(jobs.sweepQuarantine()).isEqualTo(2);
    assertThat(row(live).get("state")).isEqualTo("PROCESSING");
    assertThat(row(live).get("quarantine_cleaned_at")).isNull();
    assertThat(row(dead).get("state")).isEqualTo("EXPIRED");
    assertThat(row(dead).get("lease_token")).isNull();
    assertThat(row(claimed).get("quarantine_cleaned_at")).isNull();
    assertThat(row(takeover).get("quarantine_cleaned_at")).isNotNull();
    assertThat(deletes(raw(live))).isZero();
  }
  @Test void sweepTreatsMissingObjectAsSuccess() {
    UUID p = createProduct("s3");
    UUID id = upload(p, "APPROVED", "1 hour", "1 hour");
    assertThat(jobs.sweepQuarantine()).isEqualTo(1);
    assertThat(row(id).get("quarantine_cleaned_at")).isNotNull();
    assertThat(row(id).get("quarantine_attempts")).isEqualTo(0);
    assertThat(row(id).get("state")).isEqualTo("APPROVED");
  }
  @Test void concurrentSweepersNeverClaimSameRow() throws Exception {
    UUID p = createProduct("s4");
    var ids = new ArrayList<UUID>();
    for (int i = 0; i < 100; i++) ids.add(upload(p, "REJECTED", "1 hour", "1 hour"));
    var pool = Executors.newFixedThreadPool(2);
    try (var c = DriverManager.getConnection(postgres.getJdbcUrl(), "postgres", "postgres")) {
      // Another sweeper's claim transaction still holds 10 rows; live sweepers must skip, not wait.
      c.setAutoCommit(false);
      try (var s = c.prepareStatement("select id from media_uploads where id = any(?) for update")) {
        s.setArray(1, c.createArrayOf("uuid", ids.subList(0, 10).toArray())); s.executeQuery();
      }
      var start = new CountDownLatch(1);
      var futures = new ArrayList<Future<Integer>>();
      for (int t = 0; t < 2; t++) futures.add(pool.submit(() -> { start.await(); return jobs.sweepQuarantine(); }));
      start.countDown();
      int total = 0;
      for (var f : futures) total += f.get(20, TimeUnit.SECONDS);
      assertThat(total).isEqualTo(90);
      c.rollback();
    } finally { pool.shutdownNow(); }
    assertThat(jobs.sweepQuarantine()).isEqualTo(10);
    assertThat(jdbc.queryForObject("select count(*) from media_uploads where quarantine_cleaned_at is null", Integer.class)).isZero();
    for (UUID id : ids) assertThat(deletes(raw(id))).as("deletes of one row").isEqualTo(1);
  }
  @Test void sweepRetriesAfterStorageError() {
    UUID p = createProduct("s5");
    UUID id = upload(p, "REJECTED", "1 hour", "1 hour");
    doThrow(new MediaStorage.Unavailable()).when(storage).delete(anyString());
    assertThat(jobs.sweepQuarantine()).isEqualTo(1);
    var r = row(id);
    assertThat(r.get("quarantine_attempts")).isEqualTo(1);
    assertThat(r.get("quarantine_cleaned_at")).isNull();
    assertThat(r.get("quarantine_lease_token")).isNull();
    assertThat(jdbc.queryForObject("select quarantine_next_retry_at > now() + interval '1 minute' from media_uploads where id=?", Boolean.class, id)).isTrue();
    assertThat(jobs.sweepQuarantine()).isZero(); // backoff not due yet
    doCallRealMethod().when(storage).delete(anyString());
    jdbc.update("update media_uploads set quarantine_next_retry_at=now() where id=?", id);
    assertThat(jobs.sweepQuarantine()).isEqualTo(1);
    assertThat(row(id).get("quarantine_cleaned_at")).isNotNull();
  }
  @Test void sweepErrorAfterLeaseOutlivedStillBacksOff() {
    UUID p = createProduct("s6");
    UUID id = upload(p, "REJECTED", "1 hour", "1 hour");
    doAnswer(inv -> { // slow S3: lease expires before the delete fails
      jdbc.update("update media_uploads set quarantine_lease_until=now() - interval '1 second' where id=?", id);
      throw new MediaStorage.Unavailable();
    }).when(storage).delete(anyString());
    assertThat(jobs.sweepQuarantine()).isEqualTo(1);
    assertThat(row(id).get("quarantine_attempts")).isEqualTo(1);
    assertThat(jdbc.queryForObject("select quarantine_next_retry_at > now() + interval '1 minute' from media_uploads where id=?", Boolean.class, id)).isTrue();
    assertThat(jobs.sweepQuarantine()).isZero();
  }
  @Test void gcDeletesDetachedAfterSevenDaysOnly() {
    UUID p = createProduct("g1");
    UUID six = asset(p), eight = asset(p), neverAttached = asset(p);
    jdbc.update("update media_assets set detached_at=now() - interval '6 days', approved_at=now() - interval '30 days' where id=?", six);
    jdbc.update("update media_assets set detached_at=now() - interval '8 days', approved_at=now() - interval '30 days' where id=?", eight);
    jdbc.update("update media_assets set approved_at=now() - interval '8 days' where id=?", neverAttached);
    assertThat(jobs.collectGarbage()).isEqualTo(2);
    assertThat(availability(six)).isEqualTo("AVAILABLE");
    assertThat(storage.head(image(six))).isPresent();
    for (UUID gone : List.of(eight, neverAttached)) {
      assertThat(availability(gone)).isEqualTo("DELETED");
      assertThat(storage.head(image(gone))).isEmpty();
      assertThat(storage.head(thumb(gone))).isEmpty();
    }
    assertThat(jdbc.queryForObject("select lease_token is null from media_job_leases", Boolean.class)).isTrue();
  }
  @Test void gcNeverDeletesAttachedAsset() {
    UUID p = createProduct("g2");
    UUID c = UUID.fromString(data(call("POST", "/collections", Map.of("name_vi", "Bộ", "name_en", "Col", "slug", "c-g2", "items", List.of())), 201).path("id").asText());
    UUID onProduct = asset(p);
    jdbc.update("insert into product_images(product_id,asset_id,alt_vi,alt_en,sort_order) values (?,?,'Ảnh','Image',0)", p, onProduct);
    UUID cid = UUID.randomUUID();
    jdbc.update("insert into media_uploads(id,actor_id,target_type,collection_id,filename,content_type,size_bytes,quarantine_key,put_expires_at,"
        + "complete_deadline,state,terminal_at,quarantine_cleaned_at) values (?,?,'COLLECTION',?,'synthetic.jpg','image/jpeg',1,?,now(),now() + interval '24 hours','APPROVED',now(),now())",
        cid, ACTOR, c, raw(cid));
    jdbc.update("insert into media_assets(id,image_key,thumb_key,image_content_type,image_size_bytes,image_width,image_height,image_sha256,"
        + "thumb_content_type,thumb_size_bytes,thumb_width,thumb_height,thumb_sha256) values (?,?,?,'image/jpeg',1,1,1,?,'image/jpeg',1,1,1,?)",
        cid, image(cid), thumb(cid), "0".repeat(64), "0".repeat(64));
    jdbc.update("insert into lookbook_images(collection_id,asset_id,sort_order) values (?,?,0)", c, cid);
    jdbc.update("update media_assets set approved_at=now() - interval '30 days', detached_at=now() - interval '20 days'");
    assertThat(jobs.collectGarbage()).isZero();
    assertThat(availability(onProduct)).isEqualTo("AVAILABLE");
    assertThat(availability(cid)).isEqualTo("AVAILABLE");
    assertThat(storage.head(image(onProduct))).isPresent();
    assertThat(deletes(image(cid))).isZero();
  }
  @Test void gcDeletesPartialObjectsOfTerminalUpload() {
    UUID p = createProduct("g3");
    UUID rejected = upload(p, "REJECTED", "8 days", "8 days"), recent = upload(p, "EXPIRED", "6 days", "6 days");
    jdbc.update("update media_uploads set quarantine_cleaned_at=now()");
    storage.putIfAbsent(image(rejected), new byte[] {1}, "image/jpeg");
    storage.putIfAbsent(thumb(rejected), new byte[] {1}, "image/jpeg");
    clearInvocations(storage);
    assertThat(jobs.collectGarbage()).isEqualTo(1);
    assertThat(storage.head(image(rejected))).isEmpty();
    assertThat(storage.head(thumb(rejected))).isEmpty();
    assertThat(row(rejected).get("partial_cleaned_at")).isNotNull();
    assertThat(row(recent).get("partial_cleaned_at")).isNull();
    verify(storage).delete(image(rejected));
    verify(storage).delete(thumb(rejected));
    verify(storage, org.mockito.Mockito.times(2)).head(anyString());
    verifyNoMoreInteractions(storage); // keys derived from upload_id; no listing
    assertThat(jobs.collectGarbage()).isZero();
  }
  @Test void gcRetriesStorageErrorKeepingDeleting() {
    UUID p = createProduct("g4");
    UUID a = asset(p);
    jdbc.update("update media_assets set approved_at=now() - interval '8 days' where id=?", a);
    doThrow(new MediaStorage.Unavailable()).when(storage).delete(anyString());
    assertThat(jobs.collectGarbage()).isEqualTo(1);
    var r = jdbc.queryForMap("select availability, gc_attempts, gc_error, gc_next_retry_at > now() as later from media_assets where id=?", a);
    assertThat(r).containsEntry("availability", "DELETING").containsEntry("gc_attempts", 1).containsEntry("gc_error", "STORAGE_UNAVAILABLE").containsEntry("later", true);
    doCallRealMethod().when(storage).delete(anyString());
    jdbc.update("update media_assets set gc_next_retry_at=now() where id=?", a);
    assertThat(jobs.collectGarbage()).isEqualTo(1);
    assertThat(availability(a)).isEqualTo("DELETED");
    jdbc.update("update media_assets set availability='DELETING', gc_attempts=10, gc_next_retry_at=now() where id=?", a);
    assertThat(jobs.collectGarbage()).isZero(); // retry budget exhausted: log/metric only
  }
  @Test void attachBlocksWhileDeleting() {
    UUID p = createProduct("g5");
    UUID a = asset(p);
    jdbc.update("update media_assets set availability='DELETING' where id=?", a);
    var body = new HashMap<String, Object>(Map.of("expected_version", 0, "images",
        List.of(Map.of("asset_id", a, "alt_vi", "Ảnh", "alt_en", "Image", "sort_order", 0))));
    var r = send("PUT", "/admin/api/v1/catalog/products/" + p + "/images", token("catalog.write"), null, mapper.writeValueAsString(body));
    data(r, 400);
    assertThat(json(r).path("errors").get(0).path("field").asText()).isEqualTo("images[0].asset_id");
  }
  @Test void gcIsSingleRunner() {
    UUID p = createProduct("g6");
    UUID a = asset(p);
    jdbc.update("update media_assets set approved_at=now() - interval '8 days' where id=?", a);
    jdbc.update("update media_job_leases set lease_token=gen_random_uuid(), lease_until=now() + interval '10 minutes'");
    assertThat(jobs.collectGarbage()).isZero();
    assertThat(availability(a)).isEqualTo("AVAILABLE");
  }
}
