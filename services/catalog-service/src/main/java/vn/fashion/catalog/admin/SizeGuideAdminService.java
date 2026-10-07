package vn.fashion.catalog.admin;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import vn.fashion.catalog.web.Api;

@Service
public class SizeGuideAdminService {
  public record Table(List<String> columns, List<List<String>> rows) { }
  public record Guide(UUID id, UUID categoryId, String locale, String guidelineHtml, Table tableJson, long version) { }
  private final JdbcClient jdbc;
  private final ObjectMapper json;
  public SizeGuideAdminService(JdbcClient jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }
  void requireLocale(String locale) {
    if (!"vi".equals(locale) && !"en".equals(locale)) throw new Api.Problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "INVALID_FIELDS",
        List.of(new Api.FieldError("locale", "must be vi or en")));
  }
  private Guide map(ResultSet rs, int row) throws SQLException {
    return new Guide(rs.getObject("id", UUID.class), rs.getObject("category_id", UUID.class), rs.getString("locale"), rs.getString("guideline_html"),
        json.readValue(rs.getString("table_json"), Table.class), rs.getLong("version"));
  }
  public Guide load(UUID categoryId, String locale) {
    requireLocale(locale);
    return jdbc.sql("select * from size_guides where category_id=:category and locale=:locale")
        .param("category", categoryId).param("locale", locale).query(this::map).optional()
        .orElseThrow(() -> TaxonomyService.notFound("SIZE_GUIDE_NOT_FOUND"));
  }
}
