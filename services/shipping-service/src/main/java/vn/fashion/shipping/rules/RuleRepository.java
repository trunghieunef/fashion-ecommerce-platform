package vn.fashion.shipping.rules;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Repository
public class RuleRepository {
  public record Rule(UUID id, String name, String ruleType, int priority, JsonNode config, boolean active, long version, Instant updatedAt) { }
  private static final String COLUMNS = "id, name, rule_type, priority, config_json::text as config, active, version, updated_at";
  private final JdbcClient jdbc;
  private final ObjectMapper json;
  public RuleRepository(JdbcClient jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

  private Rule map(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new Rule(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("rule_type"), rs.getInt("priority"),
        json.readTree(rs.getString("config")), rs.getBoolean("active"), rs.getLong("version"), rs.getTimestamp("updated_at").toInstant());
  }
  /** Fee order: ACTIVE, lowest priority first, id breaks ties so the choice is deterministic. */
  public List<Rule> active() {
    return jdbc.sql("select " + COLUMNS + " from shipping_rules where active order by priority, id").query((rs, n) -> map(rs)).list();
  }
  public List<Rule> all() {
    return jdbc.sql("select " + COLUMNS + " from shipping_rules order by priority, id").query((rs, n) -> map(rs)).list();
  }
  public Optional<Rule> lock(UUID id) {
    return jdbc.sql("select " + COLUMNS + " from shipping_rules where id = :id for update").param("id", id).query((rs, n) -> map(rs)).optional();
  }
  public void insert(UUID id, String name, String type, int priority, JsonNode config, boolean active) {
    jdbc.sql("insert into shipping_rules(id, name, rule_type, priority, config_json, active) values (:id, :n, :t, :p, cast(:c as jsonb), :a)")
        .param("id", id).param("n", name).param("t", type).param("p", priority).param("c", json.writeValueAsString(config)).param("a", active).update();
  }
  public void update(UUID id, String name, String type, int priority, JsonNode config, boolean active) {
    jdbc.sql("""
        update shipping_rules set name = :n, rule_type = :t, priority = :p, config_json = cast(:c as jsonb), active = :a,
          version = version + 1, updated_at = now() where id = :id""")
        .param("id", id).param("n", name).param("t", type).param("p", priority).param("c", json.writeValueAsString(config)).param("a", active).update();
  }
  /** Append-only audit row with the resulting rule. */
  public void audit(Rule after, UUID actor, String reason) {
    jdbc.sql("insert into shipping_rule_changes(id, rule_id, version, actor_id, reason, snapshot) values (:id, :r, :v, :a, :why, cast(:s as jsonb))")
        .param("id", UUID.randomUUID()).param("r", after.id()).param("v", after.version()).param("a", actor).param("why", reason)
        .param("s", json.writeValueAsString(after)).update();
  }
  public Rule get(UUID id) {
    return jdbc.sql("select " + COLUMNS + " from shipping_rules where id = :id").param("id", id).query((rs, n) -> map(rs)).single();
  }
}
