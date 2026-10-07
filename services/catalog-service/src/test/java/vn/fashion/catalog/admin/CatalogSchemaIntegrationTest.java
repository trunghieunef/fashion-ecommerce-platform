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
class CatalogSchemaIntegrationTest {
  static final String SEED = "00000000-0000-4000-8000-000000000001";
  static final UUID SAMPLE = UUID.randomUUID();
  static final UUID LEGACY = UUID.randomUUID();
  @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11")
      .withDatabaseName("catalog").withUsername("postgres").withPassword("postgres")
      .withInitScript("catalog-test-init.sql");

  @BeforeAll static void migrate() throws SQLException {
    Flyway.configure().dataSource(postgres.getJdbcUrl(), "catalog_migration", "catalog_migration_test")
        .target("1").load().migrate();
    sql("insert into products(id,slug,status,name_vi,name_en) values ('" + SAMPLE + "','sample','ACTIVE','Mẫu','Sample')");
    Flyway.configure().dataSource(postgres.getJdbcUrl(), "catalog_migration", "catalog_migration_test")
        .target("2").load().migrate();
    sql("insert into product_variants(id,product_id,sku,size,color,weight_grams) values ('" + LEGACY + "','" + SAMPLE + "','legacy-m','M','Blue',100)");
    Flyway.configure().dataSource(postgres.getJdbcUrl(), "catalog_migration", "catalog_migration_test").load().migrate();
  }

  @BeforeEach void clearVariants() throws SQLException {
    sql("delete from product_variants where id <> '" + LEGACY + "'");
  }

  static Connection connection() throws SQLException {
    return DriverManager.getConnection(postgres.getJdbcUrl(), "postgres", "postgres");
  }
  static void sql(String statement) throws SQLException {
    try (var connection = connection(); var command = connection.createStatement()) { command.execute(statement); }
  }
  UUID product() throws SQLException {
    UUID id = UUID.randomUUID();
    sql("insert into products(id,slug,status,name_vi,name_en,category_id,base_price) values ('" + id
        + "','" + id + "','DRAFT','Mẫu','Sample','" + SEED + "',0)");
    return id;
  }
  UUID variant(UUID product, String sku, String size) throws SQLException {
    UUID id = UUID.randomUUID();
    sql("insert into product_variants(id,product_id,sku,size,color,weight_grams) values ('" + id
        + "','" + product + "','" + sku + "','" + size + "','Blue',100)");
    return id;
  }

  @Test void sampleRowFromV001SurvivesWithSeedCategory() throws SQLException {
    try (var c = connection(); var s = c.createStatement();
         var r = s.executeQuery("select category_id,base_price,status from products where id='" + SAMPLE + "'")) {
      assertThat(r.next()).isTrue();
      assertThat(r.getString(1)).isEqualTo(SEED);
      assertThat(r.getLong(2)).isZero();
      assertThat(r.getString(3)).isEqualTo("ACTIVE");
    }
  }
  @Test void skuSizeColorAndProductAreImmutable() throws SQLException {
    UUID id = variant(product(), "IMMUTABLE", "M");
    UUID other = product();
    for (String change : new String[]{"sku='X'", "size='XL'", "color='Red'", "product_id='" + other + "'"}) {
      assertThatThrownBy(() -> sql("update product_variants set " + change + " where id='" + id + "'"))
          .isInstanceOf(SQLException.class).hasMessageContaining("immutable");
    }
    sql("update product_variants set price_override=100 where id='" + id + "'");
  }
  @Test void duplicateSkuViolatesNamedConstraint() throws SQLException {
    variant(product(), "DUPLICATE", "M");
    assertThatThrownBy(() -> variant(product(), "DUPLICATE", "L"))
        .hasMessageContaining("product_variants_sku_key");
  }
  @Test void legacySkuSurvivesUpgradeAndBlocksCaseInsensitiveDuplicate() throws SQLException {
    sql("update product_variants set price_override=100 where id='" + LEGACY + "'");
    try (var c = connection(); var s = c.createStatement(); var r = s.executeQuery("select sku from product_variants where id='" + LEGACY + "'")) {
      assertThat(r.next()).isTrue(); assertThat(r.getString(1)).isEqualTo("legacy-m");
    }
    assertThatThrownBy(() -> variant(product(), "LEGACY-M", "L")).hasMessageContaining("product_variants_sku_key_ci");
  }
  @Test void directInsertRequiresCanonicalUppercaseAsciiSku() throws SQLException {
    UUID p = product();
    for (String invalid : new String[]{"lower", " MIXED ", "", "A B", "ß", "ı", "İ", "Å", ".A"})
      assertThatThrownBy(() -> variant(p, invalid, "M")).hasMessageContaining("canonical uppercase ASCII");
    variant(p, "SKU-I._1", "M");
  }
  @Test void caseCollisionStopsMigrationWithoutChangingExistingRows() throws SQLException {
    sql("create database catalog_collision owner catalog_migration");
    String url = postgres.getJdbcUrl().replace("/catalog", "/catalog_collision");
    Flyway.configure().dataSource(url, "catalog_migration", "catalog_migration_test").target("2").load().migrate();
    try (var c = DriverManager.getConnection(url, "postgres", "postgres"); var s = c.createStatement()) {
      s.execute("insert into products(id,slug,status,name_vi,name_en,category_id,base_price) values ('" + SAMPLE + "','collision','DRAFT','Test','Test','" + SEED + "',0)");
      s.execute("insert into product_variants(id,product_id,sku,size,color,weight_grams) values (gen_random_uuid(),'" + SAMPLE + "','case','M','Blue',100),(gen_random_uuid(),'" + SAMPLE + "','CASE','L','Blue',100)");
      assertThatThrownBy(() -> Flyway.configure().dataSource(url, "catalog_migration", "catalog_migration_test").load().migrate())
          .hasMessageContaining("product_variants_sku_key_ci");
      try (var r = s.executeQuery("select count(*),count(*) filter (where sku='case'),count(*) filter (where sku='CASE') from product_variants")) {
        assertThat(r.next()).isTrue(); assertThat(r.getInt(1)).isEqualTo(2); assertThat(r.getInt(2)).isEqualTo(1); assertThat(r.getInt(3)).isEqualTo(1);
      }
    }
  }
  @Test void duplicateSizeColorPerProductViolatesNamedConstraint() throws SQLException {
    UUID p = product();
    variant(p, "A", "M");
    assertThatThrownBy(() -> variant(p, "B", "M"))
        .hasMessageContaining("product_variants_product_size_color_key");
  }
  @Test void weightMustBePositiveAndPricesNonNegative() throws SQLException {
    UUID p = product();
    UUID v = variant(p, "CHECKS", "M");
    for (String change : new String[]{"weight_grams=0", "price_override=-1"}) {
      assertThatThrownBy(() -> sql("update product_variants set " + change + " where id='" + v + "'"))
          .hasMessageContaining("check constraint");
    }
    assertThatThrownBy(() -> sql("update products set base_price=-1 where id='" + p + "'"))
        .hasMessageContaining("check constraint");
  }
  @Test void runtimeRoleCannotChangeAuditLogs() throws SQLException {
    try (var c = DriverManager.getConnection(postgres.getJdbcUrl(), "catalog_runtime", "catalog_runtime_test");
         var s = c.createStatement()) {
      s.execute("insert into audit_logs(id,action,resource_type,resource_id,request_id) values (gen_random_uuid(),'test','product','sample',gen_random_uuid())");
      for (String command : new String[]{"update audit_logs set action='bad'", "delete from audit_logs", "truncate audit_logs"}) {
        assertThatThrownBy(() -> s.execute(command)).hasMessageContaining("permission denied");
      }
    }
  }
  @Test void categoryCannotBeItsOwnParent() {
    UUID id = UUID.randomUUID();
    assertThatThrownBy(() -> sql("insert into categories(id,parent_id,name_vi,name_en,slug) values ('"
        + id + "','" + id + "','Mẫu','Sample','" + id + "')"))
        .hasMessageContaining("check constraint");
  }
}
