package vn.fashion.catalog.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.net.URI;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

class UploadCompleteIntegrationTest extends MediaTestSupport {
  private static final String UPLOADS = "/admin/api/v1/catalog/images/uploads";
  @MockitoSpyBean MediaStorage storage;
  @Autowired UploadService service;
  @Autowired S3Client s3;
  @Autowired MediaProperties props;

  record Upload(String id, URI url, byte[] bytes, String contentType) {
    String raw() { return "quarantine/" + id + "/raw"; }
    String image() { return "approved/" + id + "/image"; }
    String thumb() { return "approved/" + id + "/thumb"; }
  }

  private Upload intent(String slug, byte[] bytes, String contentType) {
    var product = createProduct(slug);
    var body = Map.of("filename", "a.img", "content_type", contentType, "size_bytes", bytes.length,
        "target_type", "PRODUCT", "target_id", product.toString());
    var d = data(send("POST", UPLOADS, token("catalog.write"), UUID.randomUUID().toString(), mapper.writeValueAsString(body)), 201);
    return new Upload(d.path("upload_id").asText(), URI.create(d.path("put_url").asText()), bytes, contentType);
  }
  private void put(Upload u, byte[] bytes) {
    assertThat(putSigned(u.url(), Map.of("Content-Type", u.contentType(), "Content-Length", String.valueOf(bytes.length)), bytes)).isEqualTo(200);
  }
  private Upload uploaded(String slug, byte[] bytes, String contentType) {
    var u = intent(slug, bytes, contentType);
    put(u, bytes);
    return u;
  }
  private HttpResponse<String> complete(String token, String id) { return send("POST", UPLOADS + "/" + id + "/complete", token, null, null); }
  private HttpResponse<String> complete(String id) { return complete(token("catalog.write"), id); }
  private Map<String, Object> row(String id) {
    return jdbc.queryForMap("select state, attempt, lease_token, lease_until, reason_code, terminal_at, quarantine_cleaned_at from media_uploads where id=?::uuid", id);
  }
  private int audits(String action) { return jdbc.queryForObject("select count(*) from audit_logs where action=?", Integer.class, action); }
  private int assets() { return jdbc.queryForObject("select count(*) from media_assets", Integer.class); }
  private void expectError(HttpResponse<String> res, int status, String code, String message) {
    assertThat(res.statusCode()).withFailMessage(res.body()).isEqualTo(status);
    assertThat(json(res).path("code").asText()).isEqualTo(code);
    assertThat(json(res).path("message").asText()).isEqualTo(message);
  }
  private byte[] object(String key) { return s3.getObjectAsBytes(b -> b.bucket(props.bucket()).key(key)).asByteArray(); }
  private boolean exists(String key) {
    try { s3.headObject(b -> b.bucket(props.bucket()).key(key)); return true; }
    catch (software.amazon.awssdk.services.s3.model.NoSuchKeyException e) { return false; }
    catch (software.amazon.awssdk.services.s3.model.S3Exception e) { if (e.statusCode() == 404) return false; throw e; }
  }
  private void rawPut(String key, byte[] bytes, String contentType) {
    s3.putObject(b -> b.bucket(props.bucket()).key(key).contentType(contentType), RequestBody.fromBytes(bytes));
  }
  private static String sha(byte[] b) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b)); }
    catch (Exception e) { throw new AssertionError(e); }
  }

  @Test void completeApprovesAndStoresActualMetadata() {
    var u = uploaded("c-1", png(40, 20), "image/png");
    var d = data(complete(u.id()), 200);
    assertThat(d.path("asset_id").asText()).isEqualTo(u.id()); // 200 data is ApprovedImageAsset (OpenAPI)
    assertThat(row(u.id()).get("state")).isEqualTo("APPROVED");
    String imageSha = sha(object(u.image()));
    assertThat(d.path("image").path("sha256").asText()).isEqualTo(imageSha);
    assertThat(d.path("image").path("width").asInt()).isEqualTo(40);
    assertThat(d.path("image").path("size_bytes").asLong()).isEqualTo(object(u.image()).length);
    assertThat(d.path("thumb").path("sha256").asText()).isEqualTo(sha(object(u.thumb())));
    assertThat(jdbc.queryForObject("select image_sha256 from media_assets where id=?::uuid", String.class, u.id())).isEqualTo(imageSha);
    assertThat(jdbc.queryForObject("select image_key from media_assets where id=?::uuid", String.class, u.id())).isEqualTo(u.image());
    assertThat(exists(u.raw())).isFalse();
    var r = row(u.id());
    assertThat(r.get("quarantine_cleaned_at")).isNotNull();
    assertThat(r.get("lease_token")).isNull();
    assertThat(r.get("attempt")).isEqualTo(1);
    assertThat(audits("catalog.media.upload.approve")).isEqualTo(1);
    assertThat(d.toString()).doesNotContain("quarantine/").doesNotContain("approved/");
  }

  @Test void terminalReplayDoesNotTouchStorageOrAudit() {
    var u = uploaded("c-2", png(8, 8), "image/png");
    var first = data(complete(u.id()), 200);
    clearInvocations(storage);
    var second = data(complete(u.id()), 200);
    assertThat(second).isEqualTo(first);
    verify(storage, never()).read(anyString(), anyLong());
    verify(storage, never()).head(anyString());
    verify(storage, never()).putIfAbsent(anyString(), any(), anyString());
    verify(storage, never()).delete(anyString());
    assertThat(audits("catalog.media.upload.approve")).isEqualTo(1);
  }

  @Test void reuploadRawAfterCompleteDoesNotChangeApproved() {
    var u = uploaded("c-3", png(8, 8), "image/png");
    var first = data(complete(u.id()), 200);
    byte[] approved = object(u.image());
    byte[] other = u.bytes().clone();
    other[other.length - 1] ^= 1;
    put(u, other);
    var second = data(complete(u.id()), 200);
    assertThat(second).isEqualTo(first);
    assertThat(object(u.image())).isEqualTo(approved);
  }

  @Test void missingRawBeforeDeadlineIsNotUploaded() {
    var u = intent("c-4", png(8, 8), "image/png");
    expectError(complete(u.id()), 409, "CONFLICT", "UPLOAD_NOT_UPLOADED");
    var r = row(u.id());
    assertThat(r.get("state")).isEqualTo("PENDING");
    assertThat(r.get("lease_token")).isNull();
    assertThat(r.get("lease_until")).isNull();
    put(u, u.bytes());
    assertThat(data(complete(u.id()), 200).path("asset_id").asText()).isEqualTo(u.id());
  }

  @Test void pastDeadlineExpiresWithoutStorage() {
    var u = uploaded("c-5", png(8, 8), "image/png");
    jdbc.update("update media_uploads set put_expires_at = now() - interval '25 hours', complete_deadline = now() - interval '1 hour' where id=?::uuid", u.id());
    clearInvocations(storage);
    expectError(complete(u.id()), 409, "CONFLICT", "UPLOAD_EXPIRED");
    assertThat(row(u.id()).get("state")).isEqualTo("EXPIRED");
    assertThat(row(u.id()).get("terminal_at")).isNotNull();
    expectError(complete(u.id()), 409, "CONFLICT", "UPLOAD_EXPIRED");
    verify(storage, never()).head(anyString());
    verify(storage, never()).read(anyString(), anyLong());
  }

  @Test void rejectionIsDurableAndAuditedOnce() {
    var u = uploaded("c-6", png(8, 8), "image/jpeg");
    var res = complete(u.id());
    expectError(res, 400, "VALIDATION_ERROR", "IMAGE_REJECTED");
    assertThat(json(res).path("errors").get(0).path("field").asText()).isEqualTo("upload_id");
    assertThat(json(res).path("errors").get(0).path("message").asText()).isEqualTo("IMAGE_TYPE_NOT_ALLOWED");
    assertThat(jdbc.queryForObject("select request_id::text from audit_logs where action='catalog.media.upload.reject'", String.class))
        .isEqualTo(json(res).path("metadata").path("request_id").asText());
    var r = row(u.id());
    assertThat(r.get("state")).isEqualTo("REJECTED");
    assertThat(r.get("reason_code")).isEqualTo("IMAGE_TYPE_NOT_ALLOWED");
    assertThat(r.get("lease_token")).isNull();
    clearInvocations(storage);
    var again = complete(u.id());
    expectError(again, 400, "VALIDATION_ERROR", "IMAGE_REJECTED");
    assertThat(json(again).path("errors").get(0).path("message").asText()).isEqualTo("IMAGE_TYPE_NOT_ALLOWED");
    verify(storage, never()).head(anyString());
    assertThat(audits("catalog.media.upload.reject")).isEqualTo(1);
    assertThat(exists(u.image())).isFalse();
    assertThat(assets()).isZero();
  }

  @Test void sizeMismatchRejected() {
    byte[] png = png(8, 8);
    var u = intent("c-7", png, "image/png");
    byte[] bigger = java.util.Arrays.copyOf(png, png.length + 1);
    rawPut(u.raw(), bigger, "image/png");
    var res = complete(u.id());
    expectError(res, 400, "VALIDATION_ERROR", "IMAGE_REJECTED");
    assertThat(json(res).path("errors").get(0).path("message").asText()).isEqualTo("IMAGE_SIZE_MISMATCH");
    assertThat(row(u.id()).get("reason_code")).isEqualTo("IMAGE_SIZE_MISMATCH");
  }

  @Test void activeLeaseReturnsProcessing() {
    var u = uploaded("c-8", png(8, 8), "image/png");
    jdbc.update("update media_uploads set state='PROCESSING', lease_token=gen_random_uuid(), lease_until=now() + interval '60 seconds' where id=?::uuid", u.id());
    clearInvocations(storage);
    expectError(complete(u.id()), 409, "CONFLICT", "UPLOAD_PROCESSING");
    assertThat(row(u.id()).get("state")).isEqualTo("PROCESSING");
    verify(storage, never()).head(anyString());
  }

  @Test void noSlotReturns429WithoutStateChange() throws Exception {
    var u = uploaded("c-9", png(8, 8), "image/png");
    service.slot().acquire();
    try {
      var res = complete(u.id());
      expectError(res, 429, "RATE_LIMITED", "IMAGE_PROCESSING_CAPACITY");
      assertThat(res.headers().firstValue("Retry-After")).contains("1");
      assertThat(json(res).path("metadata").path("trace_id").asText()).isNotBlank();
      var r = row(u.id());
      assertThat(r.get("state")).isEqualTo("PENDING");
      assertThat(r.get("attempt")).isEqualTo(0);
    } finally { service.slot().release(); }
    assertThat(data(complete(u.id()), 200).path("asset_id").asText()).isEqualTo(u.id());
  }

  @Test void primaryWrittenThenCommitFailsRecoversOnRetry() {
    var u = uploaded("c-10", png(30, 30), "image/png");
    doAnswer(inv -> { inv.callRealMethod(); throw new MediaStorage.Unavailable(); })
        .when(storage).putIfAbsent(eq(u.thumb()), any(), anyString());
    expectError(complete(u.id()), 503, "TEMPORARILY_UNAVAILABLE", "DEPENDENCY_UNAVAILABLE");
    assertThat(exists(u.image())).isTrue();
    assertThat(exists(u.thumb())).isTrue();
    var r = row(u.id());
    assertThat(r.get("state")).isEqualTo("PENDING");
    assertThat(r.get("lease_token")).isNull();
    assertThat(assets()).isZero();
    org.mockito.Mockito.reset(storage);
    var d = data(complete(u.id()), 200);
    assertThat(d.path("image").path("sha256").asText()).isEqualTo(sha(object(u.image())));
    assertThat(d.path("thumb").path("sha256").asText()).isEqualTo(sha(object(u.thumb())));
    verify(storage, never()).read(eq(u.raw()), anyLong());
    verify(storage, never()).head(eq(u.raw()));
    assertThat(row(u.id()).get("attempt")).isEqualTo(2);
    assertThat(audits("catalog.media.upload.approve")).isEqualTo(1);
  }

  @Test void firstWriterWinsUsesActualPrimaryBytes() {
    var u = uploaded("c-11", png(40, 40), "image/png");
    byte[] winner = png(12, 6);
    doAnswer(inv -> { rawPut(u.image(), winner, "image/png"); return inv.callRealMethod(); })
        .when(storage).putIfAbsent(eq(u.image()), any(), anyString());
    var d = data(complete(u.id()), 200);
    assertThat(object(u.image())).isEqualTo(winner);
    assertThat(d.path("image").path("sha256").asText()).isEqualTo(sha(winner));
    assertThat(d.path("image").path("width").asInt()).isEqualTo(12);
    assertThat(d.path("image").path("size_bytes").asLong()).isEqualTo(winner.length);
    assertThat(d.path("thumb").path("width").asInt()).isEqualTo(12);
    assertThat(d.path("thumb").path("height").asInt()).isEqualTo(6);
    assertThat(d.path("thumb").path("sha256").asText()).isEqualTo(sha(object(u.thumb())));
  }

  @Test void staleLeaseCannotCommit() {
    var u = uploaded("c-12", png(8, 8), "image/png");
    doAnswer(inv -> {
      jdbc.update("update media_uploads set lease_token=gen_random_uuid() where id=?::uuid", u.id());
      return inv.callRealMethod();
    }).when(storage).read(eq(u.thumb()), anyLong());
    var res = complete(u.id());
    assertThat(res.statusCode()).withFailMessage(res.body()).isNotEqualTo(200);
    expectError(res, 409, "CONFLICT", "UPLOAD_PROCESSING");
    assertThat(row(u.id()).get("state")).isEqualTo("PROCESSING");
    assertThat(assets()).isZero();
    assertThat(audits("catalog.media.upload.approve")).isZero();
  }

  private void pastDeadline(String id) {
    // Keeps CHECK complete_deadline = put_expires_at + 24h valid.
    jdbc.update("update media_uploads set put_expires_at = now() - interval '24 hours 1 second', complete_deadline = now() - interval '1 second' where id=?::uuid", id);
  }

  @Test void staleLeaseAfterThumbWriteCannotCommit() {
    var u = uploaded("c-18", png(8, 8), "image/png");
    var nextToken = UUID.randomUUID();
    doAnswer(inv -> {
      var result = inv.callRealMethod();
      jdbc.update("update media_uploads set lease_token=?, lease_until=now() + interval '120 seconds' where id=?::uuid", nextToken, u.id());
      return result;
    }).when(storage).putIfAbsent(eq(u.thumb()), any(), anyString());
    expectError(complete(u.id()), 409, "CONFLICT", "UPLOAD_PROCESSING");
    assertThat(exists(u.image())).isTrue();
    assertThat(exists(u.thumb())).isTrue();
    var current = row(u.id());
    assertThat(current.get("lease_token")).isEqualTo(nextToken);
    assertThat(current.get("terminal_at")).isNull();
    assertThat(current.get("quarantine_cleaned_at")).isNull();
    assertThat(current.get("state")).isEqualTo("PROCESSING");
    assertNothingApproved(u.id());
    assertThat(audits("catalog.media.upload.reject")).isZero();
  }
  private void assertNothingApproved(String id) {
    assertThat(row(id).get("state")).isNotEqualTo("APPROVED");
    assertThat(assets()).isZero();
    assertThat(audits("catalog.media.upload.approve")).isZero();
  }

  @Test void deadlinePassingAfterRawReadStopsBeforeApprovedWrite() {
    var u = uploaded("c-15", png(8, 8), "image/png");
    doAnswer(inv -> { var r = inv.callRealMethod(); pastDeadline(u.id()); return r; })
        .when(storage).read(eq(u.raw()), anyLong());
    expectError(complete(u.id()), 409, "CONFLICT", "UPLOAD_EXPIRED");
    verify(storage, never()).putIfAbsent(eq(u.image()), any(), anyString());
    verify(storage, never()).putIfAbsent(eq(u.thumb()), any(), anyString());
    assertThat(row(u.id()).get("state")).isEqualTo("EXPIRED");
    assertThat(row(u.id()).get("lease_token")).isNull();
    assertNothingApproved(u.id());
  }

  // Hook after the thumb write: the last pre-write fence has passed, so only the finish CAS can stop the commit.
  @Test void expiredLeaseWithoutTakeoverCannotCommit() {
    var u = uploaded("c-16", png(8, 8), "image/png");
    doAnswer(inv -> {
      var r = inv.callRealMethod();
      jdbc.update("update media_uploads set lease_until = now() - interval '1 second' where id=?::uuid", u.id());
      return r;
    }).when(storage).putIfAbsent(eq(u.thumb()), any(), anyString());
    var res = complete(u.id());
    assertThat(res.statusCode()).withFailMessage(res.body()).isNotEqualTo(200);
    assertNothingApproved(u.id());
  }

  @Test void deadlinePassingBeforeFinishExpiresAndCannotCommit() {
    var u = uploaded("c-17", png(8, 8), "image/png");
    doAnswer(inv -> { var r = inv.callRealMethod(); pastDeadline(u.id()); return r; })
        .when(storage).putIfAbsent(eq(u.thumb()), any(), anyString());
    expectError(complete(u.id()), 409, "CONFLICT", "UPLOAD_EXPIRED");
    var r = row(u.id());
    assertThat(r.get("state")).isEqualTo("EXPIRED");
    assertThat(r.get("lease_token")).isNull();
    assertThat(r.get("lease_until")).isNull();
    assertNothingApproved(u.id());
  }

  @Test void storageOutageReleasesClaim() {
    var u = uploaded("c-13", png(8, 8), "image/png");
    doThrow(new MediaStorage.Unavailable()).when(storage).head(anyString());
    expectError(complete(u.id()), 503, "TEMPORARILY_UNAVAILABLE", "DEPENDENCY_UNAVAILABLE");
    var r = row(u.id());
    assertThat(r.get("state")).isEqualTo("PENDING");
    assertThat(r.get("lease_token")).isNull();
    assertThat(r.get("lease_until")).isNull();
    assertThat(r.get("attempt")).isEqualTo(1);
  }

  @Test void foreignActorGets404() {
    var u = uploaded("c-14", png(8, 8), "image/png");
    clearInvocations(storage);
    expectError(complete(tokenFor(UUID.randomUUID(), "catalog.write"), u.id()), 404, "NOT_FOUND", "UPLOAD_NOT_FOUND");
    expectError(complete(UUID.randomUUID().toString()), 404, "NOT_FOUND", "UPLOAD_NOT_FOUND");
    assertThat(complete(token(), u.id()).statusCode()).isEqualTo(403);
    assertThat(row(u.id()).get("state")).isEqualTo("PENDING");
    verify(storage, never()).head(anyString());
  }
}
