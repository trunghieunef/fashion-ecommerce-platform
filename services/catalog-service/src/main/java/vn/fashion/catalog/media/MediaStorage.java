package vn.fashion.catalog.media;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/** S3 adapter. Errors never carry keys, URLs or credentials. */
public class MediaStorage {
  public static class Unavailable extends RuntimeException {
    public Unavailable() { super("Media storage unavailable"); }
  }

  private final S3Client s3;
  private final S3Presigner presigner;
  private final MediaProperties props;

  public MediaStorage(S3Client s3, S3Presigner presigner, MediaProperties props) {
    this.s3 = s3;
    this.presigner = presigner;
    this.props = props;
  }

  public URI presignPut(String key, String contentType, long sizeBytes, Duration ttl) {
    return guard(() -> {
      try {
        return presigner.presignPutObject(PutObjectPresignRequest.builder().signatureDuration(ttl)
            .putObjectRequest(PutObjectRequest.builder().bucket(props.bucket()).key(key)
                .contentType(contentType).contentLength(sizeBytes).build()).build()).url().toURI();
      } catch (java.net.URISyntaxException e) { throw new Unavailable(); }
    });
  }

  public Optional<Long> head(String key) {
    return guard(() -> {
      try { return Optional.of(s3.headObject(b -> b.bucket(props.bucket()).key(key)).contentLength()); }
      catch (S3Exception e) { if (e.statusCode() == 404) return Optional.empty(); throw e; }
    });
  }

  /** Reads at most maxBytes + 1 bytes so callers can detect oversize. */
  public Optional<byte[]> read(String key, long maxBytes) {
    return guard(() -> {
      try {
        return Optional.of(s3.getObjectAsBytes(b -> b.bucket(props.bucket()).key(key)
            .range("bytes=0-" + maxBytes)).asByteArray());
      } catch (S3Exception e) {
        if (e.statusCode() == 404) return Optional.empty();
        if (e.statusCode() == 416) return Optional.of(new byte[0]); // empty object
        throw e;
      }
    });
  }

  public boolean putIfAbsent(String key, byte[] bytes, String contentType) {
    return guard(() -> {
      try {
        s3.putObject(b -> b.bucket(props.bucket()).key(key).contentType(contentType).ifNoneMatch("*"),
            RequestBody.fromBytes(bytes));
        return true;
      } catch (S3Exception e) { if (e.statusCode() == 412) return false; throw e; }
    });
  }

  public void delete(String key) {
    guard(() -> s3.deleteObject(b -> b.bucket(props.bucket()).key(key)));
  }

  public Optional<ResponseInputStream<GetObjectResponse>> open(String key) {
    return guard(() -> {
      try { return Optional.of(s3.getObject(b -> b.bucket(props.bucket()).key(key))); }
      catch (S3Exception e) { if (e.statusCode() == 404) return Optional.empty(); throw e; }
    });
  }

  /** Idempotent bootstrap for local/test; staging buckets are provisioned separately. No bucket policy. */
  public void ensureBucket() {
    guard(() -> {
      try { s3.headBucket(b -> b.bucket(props.bucket())); }
      catch (S3Exception e) {
        if (e.statusCode() != 404) throw e;
        s3.createBucket(b -> b.bucket(props.bucket()));
      }
      s3.putBucketCors(b -> b.bucket(props.bucket()).corsConfiguration(c -> c.corsRules(r ->
          r.allowedOrigins(props.corsOrigins()).allowedMethods("PUT").allowedHeaders("content-type", "content-length"))));
      s3.putBucketLifecycleConfiguration(b -> b.bucket(props.bucket()).lifecycleConfiguration(c ->
          c.rules(r -> r.id("quarantine-only").status("Enabled").filter(f -> f.prefix("quarantine/"))
              .expiration(e -> e.days(props.quarantineRetentionDays())))));
      return null;
    });
  }

  private static <T> T guard(Supplier<T> call) {
    try { return call.get(); }
    catch (Unavailable e) { throw e; }
    catch (SdkException | IllegalArgumentException e) { throw new Unavailable(); }
  }
}
