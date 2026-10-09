package vn.fashion.catalog.media;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import vn.fashion.platform.work.LeaseRepository;

/**
 * Quarantine sweep (row claim, many instances) and retention GC (single runner) of CAT-03 spec section 5.
 * S3 calls never run inside a transaction or while holding a row lock. Logs/metrics carry no key or upload id.
 */
@Component
public class MediaJobs {
  static final UUID GC_ID = UUID.fromString("00000000-0000-4000-8000-00000000c003");
  static final int BATCH = 100, GC_MAX_ATTEMPTS = 10;
  private static final Logger LOG = LoggerFactory.getLogger(MediaJobs.class);
  /** Single place for "asset still in use"; selection and the locked re-check both use it. */
  private static final String UNREFERENCED = """
      not exists (select 1 from product_images pi where pi.asset_id = a.id)
      and not exists (select 1 from lookbook_images li where li.asset_id = a.id)""";
  private static final String RETENTION_DUE = "coalesce(a.detached_at, a.approved_at) + interval '7 days' <= now()";

  private record Claimed(UUID id, String imageKey, String thumbKey) { }

  private final JdbcClient jdbc;
  private final TransactionTemplate tx;
  private final MediaStorage storage;
  private final LeaseRepository leases;
  private final MeterRegistry meters;

  public MediaJobs(JdbcClient jdbc, TransactionTemplate tx, MediaStorage storage, LeaseRepository mediaJobLeases, MeterRegistry meters) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.storage = storage;
    this.leases = mediaJobLeases;
    this.meters = meters;
  }

  /** Returns the number of upload rows claimed. */
  @Scheduled(fixedDelay = 60, timeUnit = TimeUnit.SECONDS)
  public int sweepQuarantine() {
    UUID token = UUID.randomUUID();
    // Claim commits before any DeleteObject; expired-lease PROCESSING and stale PENDING become EXPIRED here.
    // CAS fences on the token only: a re-claim changes it, and an outlived lease must still record its retry/backoff.
    List<UUID> ids = tx.execute(s -> jdbc.sql("""
        with due as materialized (
          select id from media_uploads
           where quarantine_cleaned_at is null and quarantine_next_retry_at <= now()
             and (quarantine_lease_until is null or quarantine_lease_until <= now())
             and (terminal_at is not null
                  or (now() > put_expires_at + interval '24 hours' and not (state = 'PROCESSING' and lease_until > now())))
           order by quarantine_next_retry_at, id
           limit :batch
           for update skip locked)
        update media_uploads u
           set quarantine_lease_token = :token, quarantine_lease_until = now() + interval '120 seconds',
               state = case when u.terminal_at is null then 'EXPIRED' else u.state end,
               terminal_at = coalesce(u.terminal_at, now()), lease_token = null, lease_until = null
          from due where u.id = due.id
        returning u.id
        """).param("batch", BATCH).param("token", token).query(UUID.class).list());
    for (UUID id : ids) {
      try {
        storage.delete("quarantine/" + id + "/raw");
        jdbc.sql("""
            update media_uploads set quarantine_cleaned_at = now(), quarantine_lease_token = null, quarantine_lease_until = null
             where id = :id and quarantine_lease_token = :token
            """).param("id", id).param("token", token).update();
        count("sweep", "ok");
      } catch (MediaStorage.Unavailable e) {
        jdbc.sql("""
            update media_uploads set quarantine_attempts = quarantine_attempts + 1,
                   quarantine_next_retry_at = now() + make_interval(mins => least(power(2, quarantine_attempts + 1), 60)::int),
                   quarantine_lease_token = null, quarantine_lease_until = null
             where id = :id and quarantine_lease_token = :token
            """).param("id", id).param("token", token).update();
        count("sweep", "error");
        LOG.warn("Quarantine sweep delete failed: STORAGE_UNAVAILABLE");
      }
    }
    return ids.size();
  }

  /** Returns assets plus partial uploads handled; 0 when another instance holds the GC lease. */
  @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.HOURS)
  public int collectGarbage() {
    // ponytail: 30 min lease vs batch 100 x 2 deletes x 10s S3 timeout (~33 min worst case); a takeover only repeats idempotent deletes.
    var token = leases.claim(GC_ID, Duration.ofMinutes(30));
    if (token.isEmpty()) return 0;
    try {
      int assets = collectAssets();
      return assets + collectPartials(BATCH - assets);
    } finally {
      leases.complete(GC_ID, token.get(), () -> { });
    }
  }

  private int collectAssets() {
    List<Claimed> claimed = tx.execute(s -> {
      // Lock first (skip rows an attach holds), then re-check with a fresh snapshot while holding the lock.
      List<UUID> locked = jdbc.sql("select a.id from media_assets a where a.gc_next_retry_at <= now() and ("
          + "(a.availability = 'DELETING' and a.gc_attempts < :max) or (a.availability = 'AVAILABLE' and " + RETENTION_DUE
          + " and " + UNREFERENCED + ")) order by a.id limit :batch for update skip locked")
          .param("max", GC_MAX_ATTEMPTS).param("batch", BATCH).query(UUID.class).list();
      if (locked.isEmpty()) return List.of();
      return jdbc.sql("update media_assets a set availability = 'DELETING' where a.id in (:ids) and (a.availability = 'DELETING' or ("
          + "a.availability = 'AVAILABLE' and " + RETENTION_DUE + " and " + UNREFERENCED + ")) returning a.id, a.image_key, a.thumb_key")
          .param("ids", locked).query((rs, n) -> new Claimed(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3))).list();
    });
    for (var c : claimed) {
      try {
        storage.delete(c.imageKey());
        storage.delete(c.thumbKey());
        jdbc.sql("update media_assets set availability = 'DELETED', gc_error = null where id = :id and availability = 'DELETING'")
            .param("id", c.id()).update();
        count("gc", "ok");
      } catch (MediaStorage.Unavailable e) {
        int attempts = jdbc.sql("""
            update media_assets set gc_attempts = gc_attempts + 1, gc_error = 'STORAGE_UNAVAILABLE',
                   gc_next_retry_at = now() + make_interval(mins => least(power(2, gc_attempts + 1), 60)::int)
             where id = :id and availability = 'DELETING' returning gc_attempts
            """).param("id", c.id()).query(Integer.class).optional().orElse(0);
        count("gc", "error");
        if (attempts >= GC_MAX_ATTEMPTS) LOG.error("Media GC gave up after {} attempts: STORAGE_UNAVAILABLE", attempts);
        else LOG.warn("Media GC delete failed: STORAGE_UNAVAILABLE");
      }
    }
    return claimed.size();
  }

  /** EXPIRED/REJECTED uploads without an asset may have left approved objects; keys come from the id, never a listing. */
  private int collectPartials(int limit) {
    if (limit <= 0) return 0;
    List<UUID> ids = jdbc.sql("""
        select u.id from media_uploads u
         where u.state in ('EXPIRED', 'REJECTED') and u.partial_cleaned_at is null
           and u.terminal_at + interval '7 days' <= now()
           and not exists (select 1 from media_assets a where a.id = u.id)
         order by u.terminal_at, u.id limit :limit
        """).param("limit", limit).query(UUID.class).list();
    for (UUID id : ids) {
      try {
        storage.delete("approved/" + id + "/image");
        storage.delete("approved/" + id + "/thumb");
        jdbc.sql("update media_uploads set partial_cleaned_at = now() where id = :id").param("id", id).update();
        count("gc", "ok");
      } catch (MediaStorage.Unavailable e) {
        // ponytail: no partial retry counter in V005; retried next hourly run, add backoff columns if outages pile up.
        count("gc", "error");
        LOG.warn("Media GC partial delete failed: STORAGE_UNAVAILABLE");
      }
    }
    return ids.size();
  }

  private void count(String job, String outcome) {
    meters.counter("catalog.media.jobs", "job", job, "outcome", outcome).increment();
  }
}
