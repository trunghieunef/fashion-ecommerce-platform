package vn.fashion.catalog.admin;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.fashion.catalog.web.Api;

@Service
public class SizeGuideAdminService {
  public record Table(List<String> columns, List<List<String>> rows) { }
  public record Guide(UUID id, UUID categoryId, String locale, String guidelineHtml, Table tableJson, long version) { }
  public static class Request {
    public String guidelineHtml;
    public JsonNode tableJson, expectedVersion;
    @JsonIgnore public final List<String> unknownFields = new ArrayList<>();
    @JsonAnySetter public void unknown(String key, JsonNode value) { unknownFields.add(key); }
  }
  public record PutInput(UUID categoryId, String locale, String guidelineHtml, Table tableJson, Long expectedVersion) { }
  private final JdbcClient jdbc;
  private final ObjectMapper json;
  private final AdminCommands commands;
  private final AuditLog audit;
  public SizeGuideAdminService(JdbcClient jdbc, ObjectMapper json, AdminCommands commands, AuditLog audit) {
    this.jdbc = jdbc; this.json = json; this.commands = commands; this.audit = audit;
  }
  PutInput normalize(UUID categoryId, String locale, Request request) {
    requireLocale(locale);
    var errors = new ArrayList<Api.FieldError>();
    request.unknownFields.forEach(key -> errors.add(new Api.FieldError(key, "unknown field")));
    var value = request.expectedVersion;
    Long version = null;
    if (value == null || value.isNull() || !value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < 0)
      errors.add(new Api.FieldError("expected_version", "required nonnegative integer"));
    else version = value.asLong();
    String html = HtmlSanitizer.clean(request.guidelineHtml);
    if (html.length() > 20000) errors.add(new Api.FieldError("guideline_html", "maximum 20000 characters after sanitizing"));
    Table table = table(request.tableJson, errors);
    TaxonomyService.valid(errors);
    if (json.writeValueAsString(table).getBytes(StandardCharsets.UTF_8).length > 32768)
      throw new Api.Problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "INVALID_FIELDS", List.of(new Api.FieldError("table_json", "maximum 32768 UTF-8 bytes")));
    return new PutInput(categoryId, locale, html, table, version);
  }
  private Table table(JsonNode value, List<Api.FieldError> errors) {
    if (value == null || value.isNull() || !value.isObject()) {
      errors.add(new Api.FieldError("table_json", "required table object")); return null;
    }
    for (String key : value.propertyNames()) if (!List.of("columns", "rows").contains(key)) errors.add(new Api.FieldError("table_json." + key, "unknown field"));
    var columns = value.path("columns"); var rows = value.path("rows");
    var headers = new ArrayList<String>(); var cells = new ArrayList<List<String>>(); var seen = new HashSet<String>();
    if (!columns.isArray() || columns.size() < 1 || columns.size() > 20) errors.add(new Api.FieldError("table_json.columns", "must have 1..20 columns"));
    else for (int i = 0; i < columns.size(); i++) {
      String header = text(columns.get(i), "table_json.columns[" + i + "]", false, errors);
      if (columns.get(i).isTextual() && !header.isEmpty() && header.length() <= 100 && !seen.add(header.toLowerCase(Locale.ROOT)))
        errors.add(new Api.FieldError("table_json.columns[" + i + "]", "duplicate header"));
      headers.add(header);
    }
    if (!rows.isArray() || rows.size() < 1 || rows.size() > 100) errors.add(new Api.FieldError("table_json.rows", "must have 1..100 rows"));
    else for (int i = 0; i < rows.size(); i++) {
      String field = "table_json.rows[" + i + "]"; var row = rows.get(i);
      if (!row.isArray()) { errors.add(new Api.FieldError(field, "must be an array")); continue; }
      if (row.size() != columns.size()) errors.add(new Api.FieldError(field, "must match column count"));
      var normalized = new ArrayList<String>();
      for (int j = 0; j < row.size(); j++) normalized.add(text(row.get(j), field + "[" + j + "]", true, errors));
      cells.add(List.copyOf(normalized));
    }
    return new Table(List.copyOf(headers), List.copyOf(cells));
  }
  private static String text(JsonNode value, String field, boolean emptyAllowed, List<Api.FieldError> errors) {
    if (!value.isTextual()) { errors.add(new Api.FieldError(field, "must be a string")); return ""; }
    String text = value.asText().strip();
    if ((!emptyAllowed && text.isEmpty()) || text.length() > 100) errors.add(new Api.FieldError(field, "maximum 100 characters; header must not be empty"));
    return text;
  }
  void requireCategory(UUID categoryId) {
    if (!jdbc.sql("select exists(select 1 from categories where id=:id)").param("id", categoryId).query(Boolean.class).single())
      throw TaxonomyService.notFound("CATEGORY_NOT_FOUND");
  }
  AdminCommands.Replay put(UUID actor, String key, PutInput input, UUID requestId) {
    requireCategory(input.categoryId());
    return commands.create(actor, "catalog.size-guide.put", key, input, () -> {
      Guide before = null; Guide result;
      if (input.expectedVersion() == 0) {
        result = jdbc.sql("""
            insert into size_guides(id,category_id,locale,guideline_html,table_json)
            values (:id,:category,:locale,:html,cast(:table as jsonb))
            on conflict (category_id,locale) do nothing returning *
            """).param("id", UUID.randomUUID()).param("category", input.categoryId()).param("locale", input.locale())
            .param("html", input.guidelineHtml()).param("table", json.writeValueAsString(input.tableJson())).query(this::map).optional()
            .orElseThrow(() -> versionConflict());
      } else {
        before = jdbc.sql("select * from size_guides where category_id=:category and locale=:locale for update")
            .param("category", input.categoryId()).param("locale", input.locale()).query(this::map).optional().orElseThrow(() -> versionConflict());
        AdminCommands.requireVersion(input.expectedVersion(), before.version());
        result = jdbc.sql("""
            update size_guides set guideline_html=:html,table_json=cast(:table as jsonb),version=version+1,updated_at=now()
            where id=:id returning *
            """).param("id", before.id()).param("html", input.guidelineHtml()).param("table", json.writeValueAsString(input.tableJson())).query(this::map).single();
      }
      audit.record(actor, before == null ? "catalog.size-guide.create" : "catalog.size-guide.update", "size-guide", result.id(), null, before, result, requestId);
      return new AdminCommands.Result(result.id(), before == null ? 201 : 200, result);
    });
  }
  private static Api.Problem versionConflict() { return new Api.Problem(HttpStatus.CONFLICT, "VERSION_CONFLICT", "STALE_VERSION", List.of()); }
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
