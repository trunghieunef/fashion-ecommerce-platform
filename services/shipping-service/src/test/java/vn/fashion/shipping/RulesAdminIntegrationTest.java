package vn.fashion.shipping;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

/** SHP-01 spec section 5: OPS rules with shipping.rules, versioned updates, config validated per type, audited. */
class RulesAdminIntegrationTest extends ShippingTestSupport {
  static final String RULES = "/admin/api/v1/shipping/rules";
  String ops() { return opsToken("shipping.rules"); }

  @Test void createUpdateAndAudit() {
    var created = data(send("POST", RULES, """
        {"name":"Đà Nẵng","rule_type":"PROVINCE","priority":5,"config":{"provinces":["48"],"fee":20000,"cod_fee":5000},"reason":"pilot"}""",
        "Authorization", ops()), 201);
    String id = created.path("id").asString();
    assertThat(created.path("version").asLong()).isZero();
    assertThat(created.path("active").asBoolean()).isTrue();
    // The new rule wins for province 48 because priority 5 < 50.
    String v = variant(500);
    assertThat(data(send("POST", "/api/v1/shipping/fee/estimate", feeBody("48", false, v, 1)), 200).path("fee").asLong()).isEqualTo(20000);

    var updated = data(send("PUT", RULES + "/" + id, """
        {"expected_version":0,"name":"Đà Nẵng","rule_type":"PROVINCE","priority":5,"config":{"provinces":["48"],"fee":22000,"cod_fee":5000},
         "active":false,"reason":"tắt thử"}""", "Authorization", ops()), 200);
    assertThat(updated.path("version").asLong()).isEqualTo(1);
    assertThat(updated.path("active").asBoolean()).isFalse();
    assertThat(data(send("POST", "/api/v1/shipping/fee/estimate", feeBody("48", false, v, 1)), 200).path("fee").asLong()).isEqualTo(30000);

    var stale = send("PUT", RULES + "/" + id, """
        {"expected_version":0,"name":"x","rule_type":"FLAT","priority":1,"config":{"fee":1,"cod_fee":1},"reason":"stale"}""", "Authorization", ops());
    assertThat(problem(stale, 409)).isEqualTo("VERSION_CONFLICT");
    assertThat(jdbc.queryForList("select reason from shipping_rule_changes where rule_id = cast(? as uuid) order by version", String.class, id))
        .containsExactly("pilot", "tắt thử");
    assertThat(jdbc.queryForObject("select count(*) from shipping_rule_changes where actor_id is not null", Integer.class)).isEqualTo(2);
    assertThat(data(send("GET", RULES, null, "Authorization", ops()), 200)).hasSize(4);
  }

  @Test void configIsValidatedPerType() {
    String[] bad = {
        "{\"name\":\"a\",\"rule_type\":\"FLAT\",\"priority\":1,\"config\":{\"fee\":-1,\"cod_fee\":0},\"reason\":\"r\"}",
        "{\"name\":\"a\",\"rule_type\":\"FLAT\",\"priority\":1,\"config\":{\"fee\":1,\"cod_fee\":0,\"provinces\":[\"01\"]},\"reason\":\"r\"}",
        "{\"name\":\"a\",\"rule_type\":\"PROVINCE\",\"priority\":1,\"config\":{\"provinces\":[],\"fee\":1,\"cod_fee\":0},\"reason\":\"r\"}",
        "{\"name\":\"a\",\"rule_type\":\"WEIGHT\",\"priority\":1,\"config\":{\"tiers\":[{\"max_weight_grams\":null,\"fee\":1},{\"max_weight_grams\":5,\"fee\":1}],\"cod_fee\":0},\"reason\":\"r\"}",
        "{\"name\":\"a\",\"rule_type\":\"WEIGHT\",\"priority\":1,\"config\":{\"tiers\":[{\"max_weight_grams\":5,\"fee\":1},{\"max_weight_grams\":5,\"fee\":2}],\"cod_fee\":0},\"reason\":\"r\"}",
        "{\"name\":\"a\",\"rule_type\":\"ZONE\",\"priority\":1,\"config\":{},\"reason\":\"r\"}",
        "{\"name\":\"a\",\"rule_type\":\"FLAT\",\"priority\":-1,\"config\":{\"fee\":1,\"cod_fee\":0},\"reason\":\"r\"}",
        "{\"name\":\"a\",\"rule_type\":\"FLAT\",\"priority\":1,\"config\":{\"fee\":1,\"cod_fee\":0}}",
    };
    for (String body : bad) {
      var r = send("POST", RULES, body, "Authorization", ops());
      assertThat(r.statusCode()).withFailMessage(body + " -> " + r.body()).isEqualTo(400);
    }
    assertThat(jdbc.queryForObject("select count(*) from shipping_rules", Integer.class)).isEqualTo(3);
  }

  @Test void permissionsAreChecked() {
    assertThat(problem(send("GET", RULES, null), 401)).isEqualTo("INVALID_ACCESS_TOKEN");
    assertThat(problem(send("GET", RULES, null, "Authorization", opsToken("shipping.read")), 403)).isEqualTo("PERMISSION_REQUIRED");
    assertThat(problem(send("PUT", RULES + "/not-a-uuid", "{}", "Authorization", ops()), 404)).isEqualTo("RULE_NOT_FOUND");
  }
}
