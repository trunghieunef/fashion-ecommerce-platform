package vn.fashion.catalog.media;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import vn.fashion.catalog.admin.CatalogAdminTestSupport;

/** Real RustFS (S3) with per-run synthetic credentials; overrides the fake base endpoint/credentials. */
@TestPropertySource(properties = {"fashion.catalog.media.bootstrap-bucket=true",
    "fashion.catalog.media.cors-origins=http://localhost:4173"})
public abstract class MediaTestSupport extends CatalogAdminTestSupport {
  protected static final String ACCESS_KEY = random();
  protected static final String SECRET_KEY = random();
  @Container static final GenericContainer<?> rustfs = new GenericContainer<>(
      "rustfs/rustfs:1.0.1@sha256:1803faef57627e2d9c2e7d89d655d712ddded5389040054987163043fecb6a3c")
      .withExposedPorts(9000).withEnv("RUSTFS_ACCESS_KEY", ACCESS_KEY).withEnv("RUSTFS_SECRET_KEY", SECRET_KEY)
      .withEnv("RUSTFS_CONSOLE_ENABLE", "false").waitingFor(Wait.forListeningPort());

  private static String random() { return HexFormat.of().formatHex(UUID.randomUUID().toString().replace("-", "").getBytes()).substring(0, 32); }

  // Falls back to a dead address when another test class later builds a context after this container stopped.
  protected static String rustfsEndpoint() {
    return rustfs.isRunning() ? "http://" + rustfs.getHost() + ":" + rustfs.getMappedPort(9000) : "http://127.0.0.1:1";
  }

  static {
    MEDIA_OVERRIDES.put("endpoint", MediaTestSupport::rustfsEndpoint);
    MEDIA_OVERRIDES.put("public-endpoint", MediaTestSupport::rustfsEndpoint);
    MEDIA_OVERRIDES.put("access-key", () -> ACCESS_KEY);
    MEDIA_OVERRIDES.put("secret-key", () -> SECRET_KEY);
  }

  protected byte[] png(int w, int h) { return image(w, h, BufferedImage.TYPE_INT_ARGB, "png"); }
  protected byte[] jpeg(int w, int h) { return image(w, h, BufferedImage.TYPE_INT_RGB, "jpg"); }

  private static byte[] image(int w, int h, int type, String format) {
    try {
      var out = new ByteArrayOutputStream();
      ImageIO.write(new BufferedImage(w, h, type), format, out);
      return out.toByteArray();
    } catch (java.io.IOException e) { throw new AssertionError(e); }
  }

  protected int putSigned(URI url, Map<String, String> headers, byte[] body) {
    return request(url, "PUT", headers, body);
  }

  protected int request(URI url, String method, Map<String, String> headers, byte[] body) {
    try {
      var c = (HttpURLConnection) url.toURL().openConnection();
      c.setInstanceFollowRedirects(false);
      c.setConnectTimeout(5000);
      c.setReadTimeout(10000);
      c.setRequestMethod(method);
      headers.forEach(c::setRequestProperty);
      try {
        if (body != null) {
          c.setDoOutput(true);
          c.setFixedLengthStreamingMode(body.length);
          try (var out = c.getOutputStream()) { out.write(body); }
        }
        return c.getResponseCode();
      } finally { c.disconnect(); }
    } catch (java.io.IOException e) { throw new AssertionError("request failed: " + e.getClass().getSimpleName()); }
  }
}
