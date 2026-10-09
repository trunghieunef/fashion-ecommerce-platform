package vn.fashion.catalog.media;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("fashion.catalog.media")
public record MediaProperties(String bucket, URI endpoint, URI publicEndpoint, String region, String accessKey,
    String secretKey, List<String> corsOrigins, int quarantineRetentionDays, boolean bootstrapBucket,
    boolean jobsEnabled, Duration s3Timeout) {
  /** Never print credentials. */
  @Override public String toString() { return "MediaProperties[bucket=" + bucket + "]"; }
}
