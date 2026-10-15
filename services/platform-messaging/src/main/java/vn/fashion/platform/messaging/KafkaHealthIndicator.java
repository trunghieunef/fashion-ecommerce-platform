package vn.fashion.platform.messaging;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

public class KafkaHealthIndicator implements HealthIndicator {
  private final String bootstrap;
  public KafkaHealthIndicator(String bootstrap) { this.bootstrap = bootstrap; }
  @Override public Health health() {
    AdminClient admin = null;
    try {
      admin = AdminClient.create(Map.of("bootstrap.servers", bootstrap,
          "request.timeout.ms", 2000, "default.api.timeout.ms", 2000));
      admin.describeCluster().clusterId().get(2, TimeUnit.SECONDS);
      return Health.up().build();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Health.down().withDetail("error", "KAFKA_UNAVAILABLE").build();
    } catch (Exception e) {
      return Health.down().withDetail("error", "KAFKA_UNAVAILABLE").build();
    } finally { if (admin != null) admin.close(Duration.ofMillis(100)); }
  }
}
