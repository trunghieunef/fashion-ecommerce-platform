package vn.fashion.catalog.cache;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import vn.fashion.catalog.admin.CatalogAdminTestSupport;

/** Real Redis with the compose ACL (catalog user limited to local:catalog:*); cache on for subclasses. */
abstract class RedisCacheTestSupport extends CatalogAdminTestSupport {
  static final String PASSWORD = "catalog-test-password";
  @Container static final GenericContainer<?> redis = new GenericContainer<>("redis:8.2.9").withExposedPorts(6379)
      .withCommand("redis-server", "--save", "", "--appendonly", "no",
          "--user", "catalog", "on", ">" + PASSWORD, "~local:catalog:*", "+@connection", "+get", "+set", "+incr");
  @DynamicPropertySource static void redisProperties(DynamicPropertyRegistry registry) {
    registry.add("fashion.catalog.cache.enabled", () -> "true");
    registry.add("spring.data.redis.host", redis::getHost);
    registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    registry.add("spring.data.redis.username", () -> "catalog");
    registry.add("spring.data.redis.password", () -> PASSWORD);
  }

  /** redis-cli as the default user, for assertions the catalog ACL user may not run. */
  static String cli(String... args) {
    try {
      var command = new String[args.length + 1]; command[0] = "redis-cli"; System.arraycopy(args, 0, command, 1, args.length);
      return redis.execInContainer(command).getStdout().trim();
    } catch (Exception e) { throw new AssertionError(e); }
  }
  static Set<String> keys(String pattern) {
    return Arrays.stream(cli("--scan", "--pattern", pattern).split("\\R")).filter(k -> !k.isBlank()).collect(Collectors.toSet());
  }
  static void pause(boolean paused) {
    var docker = redis.getDockerClient();
    if (paused) docker.pauseContainerCmd(redis.getContainerId()).exec();
    else docker.unpauseContainerCmd(redis.getContainerId()).exec();
  }
}
