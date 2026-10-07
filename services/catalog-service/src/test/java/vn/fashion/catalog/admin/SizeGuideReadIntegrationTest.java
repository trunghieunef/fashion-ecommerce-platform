package vn.fashion.catalog.admin;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SizeGuideReadIntegrationTest extends CatalogAdminTestSupport {
  private void seed(UUID category, String locale, long version) {
    jdbc.update("insert into size_guides(id,category_id,locale,guideline_html,table_json,version) values (?,?,?,'<p>Guide</p>',?::jsonb,?)",
        UUID.randomUUID(), category, locale, "{\"columns\":[\"Size\"],\"rows\":[[\"M\"]]}", version);
  }
  private String path(UUID category, String locale) { return "/size-guides/" + category + "/" + locale; }
  @Test void guideReadRequiresWriterAndSupportsViEn() {
    for (String locale : List.of("vi", "en")) {
      seed(SEED, locale, locale.equals("vi") ? 7 : 2);
      String path = "/admin/api/v1/catalog" + path(SEED, locale);
      data(send("GET", path, null, null, null), 401);
      data(send("GET", path, token("member"), null, null), 403);
      var result = data(call("GET", path(SEED, locale), null), 200);
      UUID.fromString(result.path("id").asText()); assertThat(result.path("category_id").asText()).isEqualTo(SEED.toString());
      assertThat(result.path("locale").asText()).isEqualTo(locale); assertThat(result.path("version").asLong()).isEqualTo(locale.equals("vi") ? 7 : 2);
      assertThat(result.path("guideline_html").asText()).isEqualTo("<p>Guide</p>");
      assertThat(result.path("table_json").path("columns").get(0).asText()).isEqualTo("Size");
      assertThat(result.path("table_json").path("rows").get(0).get(0).asText()).isEqualTo("M");
    }
    assertThat(jdbc.queryForObject("select count(*) from audit_logs", Integer.class)).isZero();
  }
  @Test void inactiveCategoryGuideIsReadable() {
    jdbc.update("update categories set status='INACTIVE' where id=?", SEED); seed(SEED, "vi", 3);
    assertThat(data(call("GET", path(SEED, "vi"), null), 200).path("version").asLong()).isEqualTo(3);
  }
  @Test void missingGuideIs404AndInvalidLocaleIs400() {
    var missing = call("GET", path(SEED, "vi"), null); data(missing, 404);
    assertThat(json(missing).path("code").asText()).isEqualTo("NOT_FOUND");
    for (String locale : List.of("fr", "VI")) {
      var invalid = call("GET", path(SEED, locale), null); data(invalid, 400);
      assertThat(json(invalid).path("code").asText()).isEqualTo("VALIDATION_ERROR");
      assertThat(json(invalid).path("errors").get(0).path("field").asText()).isEqualTo("locale");
    }
  }
}
