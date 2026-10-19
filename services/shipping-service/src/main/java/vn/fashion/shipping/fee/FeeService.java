package vn.fashion.shipping.fee;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.fashion.platform.idempotency.IdempotencyStore;
import vn.fashion.shipping.deps.CatalogClient;
import vn.fashion.shipping.rules.RuleConfig;
import vn.fashion.shipping.rules.RuleRepository;
import vn.fashion.shipping.web.Api;

/**
 * SHP-01 spec sections 4-5: weight from catalog (never the client), then the ACTIVE rule with the lowest priority that
 * covers province + weight. Catalog is called before any transaction.
 */
@Service
public class FeeService {
  public record Item(UUID variantId, int quantity) { }
  public record Input(String provinceCode, List<Item> items, boolean cod) {
    /** Canonical form for the idempotency hash: items sorted by variant id. */
    String canonical() {
      return provinceCode + "|" + cod + "|" + items.stream().sorted(java.util.Comparator.comparing(Item::variantId))
          .map(i -> i.variantId() + ":" + i.quantity()).collect(Collectors.joining(","));
    }
  }
  public record Fee(UUID ruleId, long ruleVersion, int weightGrams, long fee, long codFee) { }
  public record Estimate(long fee, long codFee, String carrier, int weightGrams, boolean estimate, Instant expiresAt) { }
  public record Quote(UUID quoteId, long fee, long codFee, String carrier, int weightGrams, UUID ruleId, long ruleVersion,
                      Instant expiresAt) { }
  static final String ACTOR = "service:order-service";
  private final CatalogClient catalog;
  private final RuleRepository rules;
  private final JdbcClient jdbc;
  private final TransactionTemplate tx;
  private final IdempotencyStore keys;
  private final ObjectMapper json;
  private final Duration estimateTtl;
  private final Duration quoteTtl;

  public FeeService(CatalogClient catalog, RuleRepository rules, JdbcClient jdbc, TransactionTemplate tx, IdempotencyStore keys,
                    ObjectMapper json, @Value("${fashion.shipping.estimate-ttl:1h}") Duration estimateTtl,
                    @Value("${fashion.shipping.quote-ttl:15m}") Duration quoteTtl) {
    this.catalog = catalog; this.rules = rules; this.jdbc = jdbc; this.tx = tx; this.keys = keys; this.json = json;
    this.estimateTtl = estimateTtl; this.quoteTtl = quoteTtl;
  }

  public Estimate estimate(Input input) {
    var fee = compute(input);
    return new Estimate(fee.fee(), fee.codFee(), "SELF", fee.weightGrams(), true, Instant.now().plus(estimateTtl));
  }

  /** Same key + body replays the stored quote; another body is 409. */
  public Object quote(String key, Input input) {
    requireKey(key);
    String hash = sha256(input.canonical());
    var fee = compute(input);
    return tx.execute(status -> {
      var outcome = keys.begin(ACTOR, "shipping.quote", key, hash);
      if (outcome.status() == IdempotencyStore.Status.CONFLICT)
        throw new Api.Problem(HttpStatus.CONFLICT, "CONFLICT", "IDEMPOTENCY_KEY_REUSED", List.of());
      if (outcome.status() == IdempotencyStore.Status.COMPLETED) return (Object) json.readTree(outcome.responseBody());
      UUID id = UUID.randomUUID();
      Instant expires = jdbc.sql("select now() + cast(:ttl as interval)").param("ttl", quoteTtl.toSeconds() + " seconds")
          .query(java.sql.Timestamp.class).single().toInstant();
      jdbc.sql("""
          insert into shipping_quotes(id, input_hash, province_code, weight_grams, cod, fee, cod_fee, rule_id, rule_version, expires_at)
          values (:id, :h, :p, :w, :cod, :fee, :cf, :rule, :rv, :exp)""")
          .param("id", id).param("h", hash).param("p", input.provinceCode()).param("w", fee.weightGrams()).param("cod", input.cod())
          .param("fee", fee.fee()).param("cf", fee.codFee()).param("rule", fee.ruleId()).param("rv", fee.ruleVersion())
          .param("exp", java.sql.Timestamp.from(expires)).update();
      var quote = new Quote(id, fee.fee(), fee.codFee(), "SELF", fee.weightGrams(), fee.ruleId(), fee.ruleVersion(), expires);
      keys.finish(ACTOR, "shipping.quote", key, id, 200, json.writeValueAsString(quote));
      return quote;
    });
  }

  Fee compute(Input input) {
    var variants = catalog.variants(input.items().stream().map(Item::variantId).toList());
    var unavailable = new ArrayList<Api.FieldError>();
    long weight = 0;
    try {
      for (int i = 0; i < input.items().size(); i++) {
        var item = input.items().get(i);
        var v = variants.get(item.variantId());
        if (v == null || !v.found() || !v.active()) { unavailable.add(new Api.FieldError("items[" + i + "].variant_id", "not available")); continue; }
        weight = Math.addExact(weight, Math.multiplyExact((long) v.weightGrams(), item.quantity()));
      }
      if (weight > Integer.MAX_VALUE) throw new ArithmeticException();
    } catch (ArithmeticException e) {
      throw new Api.Problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "WEIGHT_OUT_OF_RANGE", List.of(new Api.FieldError("items", "total weight too large")));
    }
    if (!unavailable.isEmpty()) throw new Api.Problem(HttpStatus.UNPROCESSABLE_CONTENT, "UNPROCESSABLE", "VARIANT_NOT_AVAILABLE", unavailable);
    int grams = (int) weight;
    for (var rule : rules.active()) {
      var price = RuleConfig.parse(rule.ruleType(), rule.config()).price(input.provinceCode(), grams);
      if (price.isPresent())
        return new Fee(rule.id(), rule.version(), grams, price.get().fee(), input.cod() ? price.get().codFee() : 0);
    }
    throw new Api.Problem(HttpStatus.UNPROCESSABLE_CONTENT, "UNPROCESSABLE", "NO_SHIPPING_RULE",
        List.of(new Api.FieldError("province_code", "no active rule covers this destination and weight")));
  }

  /** {province_code, items:[{variant_id, quantity}], cod}: 1..100 distinct variants, quantity 1..99. */
  public static Input parse(JsonNode body) {
    var errors = new ArrayList<Api.FieldError>();
    JsonNode p = body.path("province_code");
    String province = p.isString() && RuleConfig.PROVINCE.matcher(p.asString()).matches() ? p.asString() : null;
    if (province == null) errors.add(new Api.FieldError("province_code", "must be 1..32 of A-Z a-z 0-9 _ -"));
    JsonNode cod = body.path("cod");
    if (!cod.isBoolean()) errors.add(new Api.FieldError("cod", "must be a boolean"));
    var items = new LinkedHashMap<UUID, Item>();
    JsonNode list = body.path("items");
    if (!list.isArray() || list.isEmpty() || list.size() > 100) errors.add(new Api.FieldError("items", "must have 1..100 items"));
    else for (int i = 0; i < list.size(); i++) {
      JsonNode item = list.get(i);
      UUID id = null;
      try {
        if (item.path("variant_id").isString()) {
          id = UUID.fromString(item.path("variant_id").asString());
          if (!id.toString().equals(item.path("variant_id").asString())) id = null;
        }
      } catch (IllegalArgumentException ignored) { }
      JsonNode q = item.path("quantity");
      boolean qty = q.isIntegralNumber() && q.canConvertToInt() && q.intValue() >= 1 && q.intValue() <= 99;
      if (id == null) errors.add(new Api.FieldError("items[" + i + "].variant_id", "must be a lowercase UUID"));
      if (!qty) errors.add(new Api.FieldError("items[" + i + "].quantity", "must be an integer 1..99"));
      if (id != null && qty && items.putIfAbsent(id, new Item(id, q.intValue())) != null)
        errors.add(new Api.FieldError("items[" + i + "].variant_id", "must be distinct"));
    }
    if (!errors.isEmpty()) throw new Api.Problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "INVALID_FIELDS", errors);
    return new Input(province, List.copyOf(items.values()), cod.asBoolean());
  }

  public static void requireKey(String key) {
    if (key == null || key.isBlank() || key.length() > 128)
      throw new Api.Problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "INVALID_KEY", List.of(new Api.FieldError("Idempotency-Key", "must be 1..128 characters")));
  }
  public static String sha256(String value) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
    catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
  }
}
