package vn.fashion.payment.vnpay;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// TASK:PAY-02: fixtures in tests/provider-fixtures/vnpay were hashed with openssl, independent of VnPaySigner.
class VnPaySignerTest {
  private static final String SECRET = "TEST-HASH-SECRET";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private record Fixture(Map<String, String> params, String canonical, String secureHash, JsonNode node) { }

  private static Fixture fixture(String name) throws Exception {
    JsonNode n = JSON.readTree(Files.readString(Path.of("../../tests/provider-fixtures/vnpay", name)));
    Map<String, String> params = new LinkedHashMap<>();
    n.get("params").properties().forEach(e -> params.put(e.getKey(), e.getValue().asString()));
    String canonical = n.has("canonical") ? n.get("canonical").asString() : n.get("data").asString();
    return new Fixture(params, canonical, n.get("secure_hash").asString(), n.get("params"));
  }

  private static Map<String, String> with(Map<String, String> p, String k, String v) {
    var m = new LinkedHashMap<>(p); m.put(k, v); return m;
  }

  private static Map<String, String> without(Map<String, String> p, String k) {
    var m = new LinkedHashMap<>(p); m.remove(k); return m;
  }

  private static VnPayConfig config() {
    return new VnPayConfig("TESTTMN1", SECRET, URI.create("https://pay.example/vpcpay.html"), URI.create("https://api.example/tx"),
        URI.create("http://localhost:8080/return"), URI.create("http://localhost:5173"), Duration.ofMinutes(15), "127.0.0.1",
        Duration.ofSeconds(10));
  }

  @Test void canonicalSortsEncodesAndSkipsEmptyAndHashFields() throws Exception {
    var f = fixture("create-link.json");
    assertThat(VnPaySigner.canonical(f.params())).isEqualTo(f.canonical());
    assertThat(VnPaySigner.hmac(SECRET, f.canonical())).isEqualTo(f.secureHash());
  }

  @Test void ipnFixtureVerifiesAndAnyChangedFieldFails() throws Exception {
    var p = fixture("ipn-success.json").params();
    assertThat(VnPaySigner.valid(SECRET, p)).isTrue();
    assertThat(VnPaySigner.valid(SECRET, with(p, "vnp_Amount", "27500100"))).isFalse();
    assertThat(VnPaySigner.valid("other", p)).isFalse();
    assertThat(VnPaySigner.valid(SECRET, without(p, "vnp_SecureHash"))).isFalse();
  }

  @Test void nonVnpParametersAreIgnoredForTheSignature() throws Exception {
    var p = fixture("ipn-success.json").params();
    assertThat(VnPaySigner.valid(SECRET, with(with(p, "utm", "x"), "vnp_SecureHashType", "MD5"))).isTrue();
  }

  @Test void payUrlAppendsCanonicalAndHash() throws Exception {
    var f = fixture("create-link.json");
    assertThat(VnPaySigner.payUrl(config(), f.params()))
        .isEqualTo("https://pay.example/vpcpay.html?" + f.canonical() + "&vnp_SecureHash=" + f.secureHash());
  }

  @Test void datesAreGmtPlus7() {
    assertThat(VnPaySigner.format(Instant.parse("2026-10-25T03:04:05Z"))).isEqualTo("20261025100405");
    assertThat(VnPaySigner.parse("20261025100405")).isEqualTo(Instant.parse("2026-10-25T03:04:05Z"));
  }

  @Test void queryChecksumsUseThePipeOrder() throws Exception {
    var req = fixture("querydr-request.json");
    assertThat(VnPaySigner.queryChecksumData(req.params())).isEqualTo(req.canonical());
    assertThat(VnPaySigner.hmac(SECRET, req.canonical())).isEqualTo(req.secureHash());
    var res = fixture("querydr-response-success.json");
    assertThat(VnPaySigner.queryResponseChecksumData(res.node())).isEqualTo(res.canonical());
    assertThat(VnPaySigner.hmac(SECRET, res.canonical())).isEqualTo(res.secureHash());
  }

  @Test void configHidesTheSecret() {
    assertThat(config().toString()).doesNotContain(SECRET);
    assertThat(config().enabled()).isTrue();
    assertThat(new VnPayConfig("", SECRET, null, null, null, null, null, null, null).enabled()).isFalse();
  }
}
