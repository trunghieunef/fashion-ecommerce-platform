package vn.fashion.catalog.media;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class MediaSchemaIntegrationTest {
  static final String SEED = "00000000-0000-4000-8000-000000000001";
  static final String DEADLINE = "now() + interval '24 hours'";
  static final UUID SAMPLE = UUID.randomUUID(), OLD_COLLECTION = UUID.randomUUID();
  @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11")
      .withDatabaseName("catalog").withUsername("postgres").withPassword("postgres")
      .withInitScript("catalog-test-init.sql");

  @BeforeAll static void migrate() throws SQLException {
    migrateTo("4");
    sql("insert into products(id,slug,status,name_vi,name_en,category_id,base_price) values ('" + SAMPLE + "','v005-sample','DRAFT','Mẫu','Sample','" + SEED + "',0)");
    sql("insert into collections(id,name_vi,name_en,slug) values ('" + OLD_COLLECTION + "','Cũ','Old','old-" + OLD_COLLECTION + "')");
    Flyway.configure().dataSource(postgres.getJdbcUrl(), "catalog_migration", "catalog_migration_test").load().migrate();
  }
  static void migrateTo(String version) {
    Flyway.configure().dataSource(postgres.getJdbcUrl(), "catalog_migration", "catalog_migration_test").target(version).load().migrate();
  }
  static Connection connection() throws SQLException { return DriverManager.getConnection(postgres.getJdbcUrl(), "postgres", "postgres"); }
  static void sql(String statement) throws SQLException {
    try (var c = connection(); var s = c.createStatement()) { s.execute(statement); }
  }
  static long count(String query) throws SQLException {
    try (var c = connection(); var s = c.createStatement(); var r = s.executeQuery(query)) { r.next(); return r.getLong(1); }
  }
  @BeforeEach void clear() throws SQLException {
    if (count("select count(*) from pg_class where relname='media_uploads'") == 0) return;
    sql("update collections set cover_asset_id=null; delete from lookbook_images; delete from product_images; delete from media_assets; delete from media_uploads; delete from collections where id <> '" + OLD_COLLECTION + "'");
  }
  UUID product() throws SQLException {
    UUID id = UUID.randomUUID();
    sql("insert into products(id,slug,status,name_vi,name_en,category_id,base_price) values ('" + id + "','" + id + "','DRAFT','T','T','" + SEED + "',0)");
    return id;
  }
  UUID collection() throws SQLException {
    UUID id = UUID.randomUUID();
    sql("insert into collections(id,name_vi,name_en,slug) values ('" + id + "','C','C','" + id + "')");
    return id;
  }
  /** Approved upload plus asset; exactly one of product/collection is non-null. */
  UUID asset(UUID product, UUID collection) throws SQLException {
    UUID id = UUID.randomUUID();
    sql(uploadSql(id, product == null ? "COLLECTION" : "PRODUCT", product, collection, "APPROVED", DEADLINE, true, true));
    sql("insert into media_assets(id,image_key,thumb_key,image_content_type,image_size_bytes,image_width,image_height,image_sha256,thumb_content_type,thumb_size_bytes,thumb_width,thumb_height,thumb_sha256) values ('"
        + id + "','approved/" + id + "/image','approved/" + id + "/thumb','image/jpeg',1,1,1,'" + "0".repeat(64) + "','image/jpeg',1,1,1,'" + "0".repeat(64) + "')");
    return id;
  }
  /** lease/reason flags let tests build deliberately inconsistent rows. */
  String uploadSql(UUID id, String type, UUID product, UUID collection, String state, String deadline, boolean lease, boolean reason) {
    String terminal = state.equals("APPROVED") || state.equals("REJECTED") || state.equals("EXPIRED") ? "now()" : "null";
    String leaseValues = state.equals("PROCESSING") && lease ? "gen_random_uuid(), now()" : "null, null";
    String reasonValue = state.equals("REJECTED") && reason ? "'BAD'" : "null";
    return "insert into media_uploads(id,actor_id,target_type,product_id,collection_id,filename,content_type,size_bytes,quarantine_key,put_expires_at,complete_deadline,state,terminal_at,lease_token,lease_until,reason_code) values ('"
        + id + "',gen_random_uuid(),'" + type + "'," + (product == null ? "null" : "'" + product + "'") + "," + (collection == null ? "null" : "'" + collection + "'")
        + ",'a.jpg','image/jpeg',1,'quarantine/" + id + "/raw',now()," + deadline + ",'" + state + "'," + terminal + "," + leaseValues + "," + reasonValue + ")";
  }
  String productImage(UUID product, UUID asset, int sort, String alt) {
    return "insert into product_images(product_id,asset_id,alt_vi,alt_en,sort_order) values ('" + product + "','" + asset + "','" + alt + "','a'," + sort + ")";
  }
  String lookbook(UUID collection, UUID asset) {
    return "insert into lookbook_images(collection_id,asset_id,sort_order) values ('" + collection + "','" + asset + "',0)";
  }

  @Test void upgradeFromV004KeepsDataAndAddsMediaTables() throws SQLException {
    for (String table : new String[]{"media_uploads", "media_assets", "product_images", "lookbook_images", "media_job_leases"})
      assertThat(count("select count(to_regclass('" + table + "'))")).as(table).isEqualTo(1);
    assertThat(count("select count(*) from collections where id='" + OLD_COLLECTION + "' and cover_asset_id is null")).isEqualTo(1);
    assertThat(count("select count(*) from products where id='" + SAMPLE + "'")).isEqualTo(1);
    assertThat(count("select count(*) from media_job_leases where id='00000000-0000-4000-8000-00000000c003'")).isEqualTo(1);
  }
  @Test void uploadChecksRejectBadTargetStateAndDeadline() throws SQLException {
    UUID p = product(), c = collection();
    sql(uploadSql(UUID.randomUUID(), "PRODUCT", p, null, "PENDING", DEADLINE, true, true));
    sql(uploadSql(UUID.randomUUID(), "COLLECTION", null, c, "PENDING", DEADLINE, true, true));
    assertThatThrownBy(() -> sql(uploadSql(UUID.randomUUID(), "PRODUCT", null, c, "PENDING", DEADLINE, true, true))).hasMessageContaining("check constraint");
    assertThatThrownBy(() -> sql(uploadSql(UUID.randomUUID(), "PRODUCT", p, c, "PENDING", DEADLINE, true, true))).hasMessageContaining("check constraint");
    assertThatThrownBy(() -> sql(uploadSql(UUID.randomUUID(), "PRODUCT", p, null, "PROCESSING", DEADLINE, false, true))).hasMessageContaining("check constraint");
    assertThatThrownBy(() -> sql(uploadSql(UUID.randomUUID(), "PRODUCT", p, null, "PENDING", "now() + interval '23 hours'", true, true))).hasMessageContaining("check constraint");
    assertThatThrownBy(() -> sql(uploadSql(UUID.randomUUID(), "PRODUCT", p, null, "REJECTED", DEADLINE, true, false))).hasMessageContaining("check constraint");
  }
  @Test void productImageWithAssetOfOtherProductIsRejectedByForeignKey() throws SQLException {
    UUID a = product(), b = product(), asset = asset(a, null);
    assertThatThrownBy(() -> sql(productImage(b, asset, 0, "a"))).hasMessageContaining("foreign key constraint");
    sql(productImage(a, asset, 0, "a"));
  }
  @Test void productImageWithCollectionAssetIsRejectedByForeignKey() throws SQLException {
    UUID p = product(), asset = asset(null, collection());
    assertThatThrownBy(() -> sql(productImage(p, asset, 0, "a"))).hasMessageContaining("foreign key constraint");
  }
  @Test void lookbookImageWithAssetOfOtherCollectionIsRejectedByForeignKey() throws SQLException {
    UUID a = collection(), b = collection(), asset = asset(null, a);
    assertThatThrownBy(() -> sql(lookbook(b, asset))).hasMessageContaining("foreign key constraint");
    sql(lookbook(a, asset));
  }
  @Test void coverMustBeLookbookImageOfSameCollectionAtCommit() throws SQLException {
    UUID c = collection(), asset = asset(null, c), other = asset(null, c);
    sql(lookbook(c, asset));
    sql("update collections set cover_asset_id='" + asset + "' where id='" + c + "'");
    // deferred: remove and re-add the lookbook image inside one transaction commits fine
    try (var conn = connection()) {
      conn.setAutoCommit(false);
      try (var s = conn.createStatement()) {
        s.execute("update collections set cover_asset_id=null where id='" + c + "'");
        s.execute("delete from lookbook_images where collection_id='" + c + "'");
        s.execute(lookbook(c, asset));
        s.execute("update collections set cover_asset_id='" + asset + "' where id='" + c + "'");
      }
      conn.commit();
    }
    // cover outside the lookbook is accepted by the statement but rejected at commit
    try (var conn = connection()) {
      conn.setAutoCommit(false);
      try (var s = conn.createStatement()) {
        s.execute("update collections set cover_asset_id='" + other + "' where id='" + c + "'");
      }
      assertThatThrownBy(conn::commit).hasMessageContaining("foreign key constraint");
    }
  }
  @Test void assetCanBeAttachedOnlyOnce() throws SQLException {
    UUID a = product(), asset = asset(a, null);
    sql(productImage(a, asset, 0, "a"));
    assertThatThrownBy(() -> sql(productImage(a, asset, 1, "a"))).hasMessageContaining("duplicate key");
  }
  @Test void altLengthAndSortOrderChecks() throws SQLException {
    UUID p = product(), asset = asset(p, null);
    assertThatThrownBy(() -> sql(productImage(p, asset, 0, ""))).hasMessageContaining("check constraint");
    assertThatThrownBy(() -> sql(productImage(p, asset, -1, "a"))).hasMessageContaining("check constraint");
  }
}
