package vn.fashion.catalog.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.services.s3.S3Client;

class MediaStorageIntegrationTest extends MediaTestSupport {
  @Autowired MediaStorage storage;
  @Autowired MediaProperties props;
  @Autowired S3Client s3;

  private String key() { return "quarantine/" + UUID.randomUUID() + "/raw"; }

  @Test void presignedPutAcceptsSignedHeadersOnly() {
    byte[] body = png(2, 2);
    String key = key();
    URI url = storage.presignPut(key, "image/png", body.length, Duration.ofMinutes(5));
    assertThat(putSigned(url, Map.of("Content-Type", "text/plain"), body)).isEqualTo(403);
    assertThat(putSigned(url, Map.of("Content-Type", "image/png"), new byte[body.length + 1])).isEqualTo(403);
    assertThat(storage.head(key)).isEmpty();
    assertThat(putSigned(url, Map.of("Content-Type", "image/png"), body)).isEqualTo(200);
    assertThat(storage.head(key)).contains((long) body.length);
  }

  @Test void putIfAbsentKeepsFirstWriter() {
    String key = key();
    assertThat(storage.putIfAbsent(key, new byte[] {1, 2, 3}, "image/png")).isTrue();
    assertThat(storage.putIfAbsent(key, new byte[] {9, 9}, "image/png")).isFalse();
    assertThat(storage.read(key, 100)).hasValueSatisfying(b -> assertThat(b).containsExactly(1, 2, 3));
  }

  @Test void readIsBoundedAndMissingIsEmpty() {
    String key = key();
    storage.putIfAbsent(key, new byte[10], "image/png");
    assertThat(storage.read(key, 4)).hasValueSatisfying(b -> assertThat(b).hasSize(5));
    assertThat(storage.read(key(), 4)).isEmpty();
    assertThat(storage.head(key())).isEmpty();
    assertThat(storage.open(key())).isEmpty();
  }

  @Test void deleteMissingKeySucceeds() {
    String key = key();
    storage.delete(key);
    storage.putIfAbsent(key, new byte[1], "image/png");
    storage.delete(key);
    assertThat(storage.head(key)).isEmpty();
  }

  @Test void bucketIsPrivateAndLifecycleOnlyQuarantine() {
    String key = key();
    storage.putIfAbsent(key, new byte[1], "image/png");
    URI object = URI.create(rustfsEndpoint() + "/" + props.bucket() + "/" + key);
    assertThat(request(object, "GET", Map.of(), null)).isEqualTo(403);
    assertThat(request(object, "HEAD", Map.of(), null)).isEqualTo(403);
    var rules = s3.getBucketLifecycleConfiguration(b -> b.bucket(props.bucket())).rules();
    assertThat(rules).hasSize(1);
    assertThat(rules.getFirst().filter().prefix()).isEqualTo("quarantine/");
    assertThat(rules.getFirst().expiration().days()).isEqualTo(2);
    var cors = s3.getBucketCors(b -> b.bucket(props.bucket())).corsRules();
    assertThat(cors).hasSize(1);
    assertThat(cors.getFirst().allowedOrigins()).containsExactly("http://localhost:4173");
    assertThat(cors.getFirst().allowedMethods()).containsExactly("PUT");
  }

  @Test void storageOutageMapsToUnavailable() {
    var dead = new MediaProperties(props.bucket(), URI.create("http://127.0.0.1:1"), URI.create("http://127.0.0.1:1"),
        props.region(), "x", "y", props.corsOrigins(), 2, false, false, Duration.ofSeconds(2));
    var config = new MediaConfig();
    try (var client = config.s3Client(dead); var presigner = config.s3Presigner(dead, client)) {
      var down = new MediaStorage(client, presigner, dead);
      String key = "quarantine/secret-key-marker/raw";
      assertThatThrownBy(() -> down.head(key)).isInstanceOf(MediaStorage.Unavailable.class)
          .hasMessageNotContaining("secret-key-marker").hasMessageNotContaining("127.0.0.1").hasNoCause();
      assertThatThrownBy(() -> down.read(key, 4)).isInstanceOf(MediaStorage.Unavailable.class);
      assertThatThrownBy(() -> down.putIfAbsent(key, new byte[1], "image/png")).isInstanceOf(MediaStorage.Unavailable.class);
      assertThatThrownBy(down::ensureBucket).isInstanceOf(MediaStorage.Unavailable.class);
    }
  }
}
