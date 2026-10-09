package vn.fashion.catalog.admin;

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
class CatalogCollectionsSchemaIntegrationTest {
  static final String SEED = "00000000-0000-4000-8000-000000000001";
  static final UUID SAMPLE = UUID.randomUUID(), LEGACY = UUID.randomUUID();
  @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11")
      .withDatabaseName("catalog").withUsername("postgres").withPassword("postgres")
      .withInitScript("catalog-test-init.sql");

  @BeforeAll static void migrate() throws SQLException {
    migrateTo("1");
    sql("insert into products(id,slug,status,name_vi,name_en) values ('" + SAMPLE + "','v004-sample','ACTIVE','Mẫu','Sample')");
    migrateTo("2");
    sql("insert into product_variants(id,product_id,sku,size,color,weight_grams,price_override) values ('"
        + LEGACY + "','" + SAMPLE + "','legacy-v004','M','Blue',100,123)");
    migrateTo("3");
    Flyway.configure().dataSource(postgres.getJdbcUrl(), "catalog_migration", "catalog_migration_test").load().migrate();
  }
  static void migrateTo(String version) {
    Flyway.configure().dataSource(postgres.getJdbcUrl(), "catalog_migration", "catalog_migration_test").target(version).load().migrate();
  }
  static Connection connection() throws SQLException { return DriverManager.getConnection(postgres.getJdbcUrl(), "postgres", "postgres"); }
  static void sql(String statement) throws SQLException {
    try (var c = connection(); var s = c.createStatement()) { s.execute(statement); }
  }
  @BeforeEach void requireSchemaAndClear() throws SQLException {
    try (var c = connection(); var s = c.createStatement(); var r = s.executeQuery(
        "select to_regclass('collections'),to_regclass('collection_items'),to_regclass('size_guides')")) {
      assertThat(r.next()).isTrue();
      for (int i = 1; i <= 3; i++) assertThat(r.getString(i)).as("V004 table " + i).isNotNull();
    }
    sql("delete from collection_items; delete from collections; delete from size_guides");
  }
  UUID collection() throws SQLException {
    UUID id = UUID.randomUUID();
    sql("insert into collections(id,name_vi,name_en,slug) values ('" + id + "','Bộ sưu tập','Collection','" + id + "')");
    return id;
  }
  String guide(String locale, long version) {
    return "insert into size_guides(id,category_id,locale,table_json,version) values (gen_random_uuid(),'"
        + SEED + "','" + locale + "','{\"columns\":[\"Size\"],\"rows\":[[\"M\"]]}'," + version + ")";
  }
  @Test void newTablesExistAfterV003Upgrade() throws SQLException {
    try (var c = connection(); var s = c.createStatement(); var r = s.executeQuery("select version from flyway_schema_history where success and version='004'")) {
      assertThat(r.next()).isTrue(); assertThat(r.getString(1)).isEqualTo("004");
    }
  }
  @Test void collectionDefaultsConstraintsAndItemForeignKeys() throws SQLException {
    UUID id = collection();
    try (var c = connection(); var s = c.createStatement(); var r = s.executeQuery("select status,version,cover_url from collections where id='" + id + "'")) {
      assertThat(r.next()).isTrue(); assertThat(r.getString(1)).isEqualTo("DRAFT"); assertThat(r.getLong(2)).isZero(); assertThat(r.getString(3)).isNull();
    }
    assertThatThrownBy(() -> sql("insert into collections(id,name_vi,name_en,slug) values (gen_random_uuid(),'A','A','" + id + "')")).hasMessageContaining("collections_slug_key");
    for (String invalid : new String[]{"version=-1", "status='UNKNOWN'"})
      assertThatThrownBy(() -> sql("update collections set " + invalid + " where id='" + id + "'")).hasMessageContaining("check constraint");
    assertThatThrownBy(() -> sql("insert into collection_items(collection_id,product_id,sort_order) values ('" + id + "','" + SAMPLE + "',-1)")).hasMessageContaining("check constraint");
    sql("insert into collection_items(collection_id,product_id,sort_order) values ('" + id + "','" + SAMPLE + "',2147483647)");
    UUID secondProduct = UUID.randomUUID();
    sql("insert into products(id,slug,status,name_vi,name_en,category_id,base_price) values ('" + secondProduct + "','" + secondProduct + "','DRAFT','Test','Test','" + SEED + "',0)");
    sql("insert into collection_items(collection_id,product_id,sort_order) values ('" + id + "','" + secondProduct + "',2147483647)");
    UUID other = collection();
    sql("insert into collection_items(collection_id,product_id,sort_order) values ('" + other + "','" + SAMPLE + "',2147483647)");
    assertThatThrownBy(() -> sql("insert into collection_items(collection_id,product_id) values ('" + id + "','" + SAMPLE + "')")).hasMessageContaining("collection_items_pkey");
    assertThatThrownBy(() -> sql("insert into collection_items(collection_id,product_id) values ('" + id + "',gen_random_uuid())")).hasMessageContaining("foreign key constraint");
    assertThatThrownBy(() -> sql("insert into collection_items(collection_id,product_id) values (gen_random_uuid(),'" + SAMPLE + "')")).hasMessageContaining("foreign key constraint");
  }
  @Test void guideLocaleVersionAndCategoryUniqueness() throws SQLException {
    sql("insert into size_guides(id,category_id,locale,table_json) values (gen_random_uuid(),'" + SEED + "','vi','{}')");
    try (var c = connection(); var s = c.createStatement(); var r = s.executeQuery("select version,guideline_html from size_guides where locale='vi'")) {
      assertThat(r.next()).isTrue(); assertThat(r.getLong(1)).isEqualTo(1); assertThat(r.getString(2)).isEmpty();
    }
    sql(guide("en", 1));
    assertThatThrownBy(() -> sql(guide("vi", 1))).hasMessageContaining("size_guides_category_id_locale_key");
    assertThatThrownBy(() -> sql(guide("fr", 1))).hasMessageContaining("check constraint");
    assertThatThrownBy(() -> sql(guide("vi", 0))).hasMessageContaining("check constraint");
    assertThatThrownBy(() -> sql("insert into size_guides(id,category_id,locale,table_json) values (gen_random_uuid(),gen_random_uuid(),'vi','{}')")).hasMessageContaining("foreign key constraint");
  }
  @Test void validTimeBoundsAndNullableBounds() throws SQLException {
    UUID id = collection();
    sql("update collections set start_at='2026-10-07T00:00:00Z',end_at=null where id='" + id + "'");
    sql("update collections set start_at=null,end_at='2026-10-07T00:00:00Z' where id='" + id + "'");
    for (String end : new String[]{"2026-10-07T00:00:00Z", "2026-10-06T00:00:00Z"})
      assertThatThrownBy(() -> sql("update collections set start_at='2026-10-07T00:00:00Z',end_at='" + end + "' where id='" + id + "'")).hasMessageContaining("check constraint");
    sql("update collections set start_at='2026-10-07T00:00:00Z',end_at='2026-10-08T00:00:00Z' where id='" + id + "'");
  }
  @Test void legacySkuAndSampleRowsSurviveV004() throws SQLException {
    try (var c = connection(); var s = c.createStatement(); var r = s.executeQuery("select p.id,p.status,p.base_price,v.sku,v.price_override from products p join product_variants v on v.product_id=p.id where v.id='" + LEGACY + "'")) {
      assertThat(r.next()).isTrue(); assertThat(r.getObject(1, UUID.class)).isEqualTo(SAMPLE); assertThat(r.getString(2)).isEqualTo("ACTIVE");
      assertThat(r.getLong(3)).isZero(); assertThat(r.getString(4)).isEqualTo("legacy-v004"); assertThat(r.getLong(5)).isEqualTo(123);
    }
  }
  @Test void runtimeAuditRemainsAppendOnly() throws SQLException {
    try (var c = DriverManager.getConnection(postgres.getJdbcUrl(), "catalog_runtime", "catalog_runtime_test"); var s = c.createStatement()) {
      s.execute("insert into audit_logs(id,action,resource_type,resource_id,request_id) values (gen_random_uuid(),'test','collection','sample',gen_random_uuid())");
      for (String command : new String[]{"update audit_logs set action='bad'", "delete from audit_logs", "truncate audit_logs"})
        assertThatThrownBy(() -> s.execute(command)).hasMessageContaining("permission denied");
    }
  }
}
