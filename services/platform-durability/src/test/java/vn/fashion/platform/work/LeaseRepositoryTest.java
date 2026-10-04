package vn.fashion.platform.work;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.fashion.platform.PostgresTestSupport;

class LeaseRepositoryTest extends PostgresTestSupport {
  private static final UUID WORK_ID = UUID.fromString("77777777-7777-4777-8777-777777777777");
  private static final Duration LEASE = Duration.ofSeconds(30);
  private final LeaseRepository leases = new LeaseRepository(jdbc, tx, "test_work");

  @BeforeEach
  void insertWork() {
    jdbc.sql("insert into test_work(id) values (:id)").param("id", WORK_ID).update();
  }

  @Test
  void activeLeaseBlocksSecondClaim() {
    assertThat(leases.claim(WORK_ID, LEASE)).isPresent();
    assertThat(leases.claim(WORK_ID, LEASE)).isEmpty();
  }

  @Test
  void staleLeaseCannotCompleteWork() {
    var old = leases.claim(WORK_ID, LEASE).orElseThrow();
    expireLease();
    var current = leases.claim(WORK_ID, LEASE).orElseThrow();

    assertThat(leases.complete(WORK_ID, old, () -> setResult("old"))).isFalse();
    assertThat(leases.complete(WORK_ID, current, () -> setResult("new"))).isTrue();
    assertThat(result()).isEqualTo("new");
  }

  @Test
  void expiredLeaseCannotCompleteEvenWithoutCompetitor() {
    var token = leases.claim(WORK_ID, LEASE).orElseThrow();
    expireLease();

    assertThat(leases.complete(WORK_ID, token, () -> setResult("late"))).isFalse();
    assertThat(result()).isNull();
  }

  @Test
  void failedResultWriteKeepsTheLease() {
    var token = leases.claim(WORK_ID, LEASE).orElseThrow();

    assertThatThrownBy(() -> leases.complete(WORK_ID, token, () -> {
      setResult("partial");
      throw new IllegalStateException("result write failed");
    })).isInstanceOf(IllegalStateException.class);

    assertThat(result()).isNull();
    assertThat(leases.complete(WORK_ID, token, () -> setResult("done"))).isTrue();
  }

  @Test
  void tableNameMustBeAPlainIdentifier() {
    assertThatThrownBy(() -> new LeaseRepository(jdbc, tx, "test_work; drop table x"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private void setResult(String value) {
    jdbc.sql("update test_work set result = :r where id = :id").param("r", value).param("id", WORK_ID)
        .update();
  }

  private String result() {
    return jdbc.sql("select result from test_work where id = :id").param("id", WORK_ID)
        .query(String.class).optional().orElse(null);
  }

  private void expireLease() {
    jdbc.sql("update test_work set lease_until = now() - interval '1 second' where id = :id")
        .param("id", WORK_ID).update();
  }
}
