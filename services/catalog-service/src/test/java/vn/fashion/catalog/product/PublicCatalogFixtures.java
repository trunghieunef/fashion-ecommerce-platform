package vn.fashion.catalog.product;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public class PublicCatalogFixtures {
  private final JdbcTemplate jdbc;

  public PublicCatalogFixtures(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  public UUID category(UUID parent, String slug, String status) {
    UUID id = UUID.randomUUID();
    jdbc.update("insert into categories(id,parent_id,slug,name_vi,name_en,status) values (?,?,?,'Áo','Shirt',?)",
        id, parent, slug, status);
    return id;
  }

  public UUID brand(String name, String status) {
    UUID id = UUID.randomUUID();
    jdbc.update("insert into brands(id,name,status) values (?,?,?)", id, name, status);
    return id;
  }

  public UUID product(String slug, String nameVi, UUID category, UUID brand, String status,
                      long basePrice, Instant publishedAt, long sold, String... tags) {
    UUID id = UUID.randomUUID();
    jdbc.update("insert into products(id,slug,name_vi,name_en,category_id,brand_id,status,base_price,published_at,sold_quantity,tags) "
        + "values (?,?,?,'English shirt',?,?,?,?,?,?,?)", id, slug, nameVi, category, brand, status,
        basePrice, timestamp(publishedAt), sold, tags);
    return id;
  }

  public UUID variant(UUID product, String sku, String size, String color, Long override, String status) {
    UUID id = UUID.randomUUID();
    jdbc.update("insert into product_variants(id,product_id,sku,size,color,price_override,status,weight_grams) values (?,?,?,?,?,?,?,100)",
        id, product, sku, size, color, override, status);
    return id;
  }

  public UUID image(UUID product, int sort, String availability) {
    UUID id = asset(product, null, availability);
    jdbc.update("insert into product_images(product_id,asset_id,alt_vi,alt_en,sort_order) values (?,?,'Ảnh','Image',?)", product, id, sort);
    return id;
  }

  public UUID collection(String slug, String status, Instant start, Instant end) {
    UUID id = UUID.randomUUID();
    jdbc.update("insert into collections(id,slug,name_vi,name_en,status,start_at,end_at) values (?,?,'Bộ sưu tập','Collection',?,?,?)",
        id, slug, status, timestamp(start), timestamp(end));
    return id;
  }

  public void item(UUID collection, UUID product, int sort) {
    jdbc.update("insert into collection_items(collection_id,product_id,sort_order) values (?,?,?)", collection, product, sort);
  }

  public UUID lookbook(UUID collection, int sort) {
    UUID id = asset(null, collection, "AVAILABLE");
    jdbc.update("insert into lookbook_images(collection_id,asset_id,caption_vi,caption_en,sort_order) values (?,?,'Chú thích','Caption',?)",
        collection, id, sort);
    return id;
  }

  public void sizeGuide(UUID category, String locale) {
    jdbc.update("insert into size_guides(id,category_id,locale,guideline_html,table_json) values (?,?,?,?,'{\"columns\":[\"Size\"],\"rows\":[[\"M\"]]}'::jsonb)",
        UUID.randomUUID(), category, locale, "<p>" + locale + " guide</p>");
  }

  private UUID asset(UUID product, UUID collection, String availability) {
    UUID id = UUID.randomUUID();
    jdbc.update("insert into media_uploads(id,actor_id,target_type,product_id,collection_id,filename,content_type,size_bytes,quarantine_key,put_expires_at,complete_deadline,state,terminal_at) "
        + "values (?,?,?,?,?,'synthetic.jpg','image/jpeg',1,?,now(),now()+interval '24 hours','APPROVED',now())",
        id, UUID.randomUUID(), product == null ? "COLLECTION" : "PRODUCT", product, collection, "quarantine/" + id + "/raw");
    jdbc.update("insert into media_assets(id,image_key,thumb_key,image_content_type,image_size_bytes,image_width,image_height,image_sha256,"
        + "thumb_content_type,thumb_size_bytes,thumb_width,thumb_height,thumb_sha256,availability) values (?,?,?,'image/jpeg',1,1,1,?,'image/jpeg',1,1,1,?,?)",
        id, "approved/" + id + "/image", "approved/" + id + "/thumb", "0".repeat(64), "0".repeat(64), availability);
    return id;
  }

  private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
}
