package vn.fashion.catalog.product;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class ProductQueryRepository {
  private final JdbcClient jdbc;

  public ProductQueryRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public ProductPage findActive(int limit) {
    if (limit < 1 || limit > 100) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_LIMIT");
    }

    List<ProductSummary> items = jdbc.sql("""
            select id, slug, name_vi, name_en
            from products
            where status = 'ACTIVE'
            order by created_at desc, id desc
            limit :limit
            """)
        .param("limit", limit)
        .query(this::mapProduct)
        .list();
    return new ProductPage(items, null);
  }

  private ProductSummary mapProduct(ResultSet resultSet, int rowNum) throws SQLException {
    return new ProductSummary(
        resultSet.getObject("id", java.util.UUID.class),
        resultSet.getString("slug"),
        resultSet.getString("name_vi"),
        resultSet.getString("name_en"));
  }
}
