package vn.fashion.catalog.media;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import vn.fashion.platform.work.LeaseRepository;

@Configuration
@EnableConfigurationProperties(MediaProperties.class)
public class MediaConfig {
  private static final S3Configuration PATH_STYLE = S3Configuration.builder().pathStyleAccessEnabled(true).build();

  private static StaticCredentialsProvider credentials(MediaProperties p) {
    return StaticCredentialsProvider.create(AwsBasicCredentials.create(p.accessKey(), p.secretKey()));
  }

  @Bean(destroyMethod = "close") S3Client s3Client(MediaProperties p) {
    return S3Client.builder().endpointOverride(p.endpoint()).region(Region.of(p.region()))
        .credentialsProvider(credentials(p)).serviceConfiguration(PATH_STYLE)
        .dualstackEnabled(false).fipsEnabled(false)
        .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(p.s3Timeout()).socketTimeout(p.s3Timeout()))
        .overrideConfiguration(c -> c.apiCallTimeout(p.s3Timeout())).build();
  }

  @Bean(destroyMethod = "close") S3Presigner s3Presigner(MediaProperties p, S3Client s3) {
    return S3Presigner.builder().endpointOverride(p.publicEndpoint()).region(Region.of(p.region()))
        .credentialsProvider(credentials(p)).serviceConfiguration(PATH_STYLE)
        .dualstackEnabled(false).fipsEnabled(false).disableS3ExpressSessionAuth(true).s3Client(s3).build();
  }

  @Bean MediaStorage mediaStorage(S3Client s3, S3Presigner presigner, MediaProperties p) {
    return new MediaStorage(s3, presigner, p);
  }

  @Bean LeaseRepository mediaJobLeases(JdbcClient jdbc, TransactionTemplate tx) {
    return new LeaseRepository(jdbc, tx, "media_job_leases");
  }

  /** MediaJobs' @Scheduled methods only fire when jobs are enabled; tests call them directly. */
  @Configuration @EnableScheduling @ConditionalOnBooleanProperty("fashion.catalog.media.jobs-enabled")
  static class Jobs { }

  @Bean ApplicationRunner ensureMediaBucket(MediaStorage storage, MediaProperties p) {
    return args -> {
      if (!p.bootstrapBucket()) return;
      for (int attempt = 1; ; attempt++) {
        try { storage.ensureBucket(); return; }
        catch (MediaStorage.Unavailable e) {
          if (attempt >= 10) throw e;
          Thread.sleep(1000);
        }
      }
    };
  }
}
