package vn.fashion.catalog.product;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.fashion.catalog.admin.CatalogAdminTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

class PublicDetailSnapshotIntegrationTest extends CatalogAdminTestSupport {
  @MockitoSpyBean JdbcClient client;
  @Autowired PlatformTransactionManager transactions;

  @Test void productPricesKeepFirstSnapshotWhenWriterCommitsBeforeVariantsRead() {
    var fixtures = new PublicCatalogFixtures(jdbc);
    UUID product = product(fixtures);
    UUID variant = fixtures.variant(product, "SNAPSHOT", "M", "Black", null, "ACTIVE");
    AtomicBoolean committed = commitBefore("from product_variants where", () ->
        jdbc.update("update product_variants set price_override=200000 where id=?", variant));

    var detail = data(send("GET", "/api/v1/catalog/products/" + product, null, null, null), 200);
    assertThat(committed).isTrue();
    assertThat(jdbc.queryForObject("select price_override from product_variants where id=?", Long.class, variant))
        .isEqualTo(200000);
    assertThat(detail.path("min_price").asLong()).isEqualTo(100000);
    assertThat(detail.path("max_price").asLong()).isEqualTo(100000);
    assertThat(detail.path("variants").get(0).path("effective_price").asLong()).isEqualTo(100000);
  }

  @Test void collectionCoverLookbookAndProductsKeepFirstSnapshotAfterWriterCommit() {
    var fixtures = new PublicCatalogFixtures(jdbc);
    UUID product = product(fixtures);
    fixtures.variant(product, "COLLECTION-SNAPSHOT", "M", "Black", null, "ACTIVE");
    UUID collection = fixtures.collection("snapshot", "ACTIVE", null, null);
    fixtures.item(collection, product, 0);
    UUID first = fixtures.lookbook(collection, 0);
    UUID second = fixtures.lookbook(collection, 1);
    jdbc.update("delete from lookbook_images where asset_id=?", second);
    jdbc.update("update collections set cover_asset_id=? where id=?", first, collection);
    AtomicBoolean committed = commitBefore("from lookbook_images li", () -> {
      jdbc.update("update collections set cover_asset_id=? where id=?", second, collection);
      jdbc.update("delete from lookbook_images where collection_id=?", collection);
      jdbc.update("insert into lookbook_images(collection_id,asset_id,caption_vi,sort_order) values (?,?,'New',0)", collection, second);
      jdbc.update("update products set status='INACTIVE' where id=?", product);
    });

    var detail = data(send("GET", "/api/v1/catalog/collections/snapshot", null, null, null), 200);
    assertThat(committed).isTrue();
    assertThat(jdbc.queryForObject("select cover_asset_id from collections where id=?", UUID.class, collection)).isEqualTo(second);
    assertThat(jdbc.queryForObject("select status from products where id=?", String.class, product)).isEqualTo("INACTIVE");
    assertThat(detail.path("collection").path("cover_asset_id").asText()).isEqualTo(first.toString());
    assertThat(detail.path("lookbook")).hasSize(1);
    assertThat(detail.path("lookbook").get(0).path("asset_id").asText()).isEqualTo(first.toString());
    assertThat(detail.path("products")).hasSize(1);
    assertThat(detail.path("products").get(0).path("id").asText()).isEqualTo(product.toString());
  }

  private UUID product(PublicCatalogFixtures fixtures) {
    UUID category = fixtures.category(null, "snapshot", "ACTIVE");
    return fixtures.product("snapshot", "Áo", category, null, "ACTIVE", 100000, null, 0);
  }

  private AtomicBoolean commitBefore(String nextRead, Runnable writer) {
    var committed = new AtomicBoolean();
    doAnswer(invocation -> {
      if (invocation.<String>getArgument(0).contains(nextRead) && committed.compareAndSet(false, true)) {
        // Join a separate writer transaction before allowing the next real SQL read.
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
          executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> writer.run()))
              .get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
      }
      return invocation.callRealMethod();
    }).when(client).sql(anyString());
    return committed;
  }
}
