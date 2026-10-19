package vn.fashion.shipping.rules;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import vn.fashion.shipping.web.Api;

/**
 * SHP-01 spec section 4: config_json per rule_type, validated on every write. FLAT {fee,cod_fee};
 * PROVINCE {provinces,fee,cod_fee}; WEIGHT {provinces?,tiers:[{max_weight_grams|null,fee}],cod_fee}.
 */
public record RuleConfig(String type, List<String> provinces, List<Tier> tiers, long fee, long codFee) {
  public record Tier(Integer maxWeightGrams, long fee) { }
  /** A match: the shipping fee for this weight and the COD fee of the rule. */
  public record Price(long fee, long codFee) { }
  static final long MAX_MONEY = 1_000_000_000L;
  public static final Pattern PROVINCE = Pattern.compile("[A-Za-z0-9_-]{1,32}");

  /** Empty when the rule does not cover this province/weight. */
  public java.util.Optional<Price> price(String province, int weightGrams) {
    if (!provinces.isEmpty() && !provinces.contains(province)) return java.util.Optional.empty();
    if (!"WEIGHT".equals(type)) return java.util.Optional.of(new Price(fee, codFee));
    for (var tier : tiers)
      if (tier.maxWeightGrams() == null || weightGrams <= tier.maxWeightGrams()) return java.util.Optional.of(new Price(tier.fee(), codFee));
    return java.util.Optional.empty();
  }

  public static RuleConfig parse(String type, JsonNode config) {
    var errors = new ArrayList<Api.FieldError>();
    if (type == null || !Set.of("FLAT", "PROVINCE", "WEIGHT").contains(type))
      throw invalid(List.of(new Api.FieldError("rule_type", "must be FLAT, PROVINCE or WEIGHT")));
    if (config == null || !config.isObject()) throw invalid(List.of(new Api.FieldError("config", "must be an object")));
    var allowed = switch (type) {
      case "FLAT" -> Set.of("fee", "cod_fee");
      case "PROVINCE" -> Set.of("provinces", "fee", "cod_fee");
      default -> Set.of("provinces", "tiers", "cod_fee");
    };
    for (String name : config.propertyNames()) if (!allowed.contains(name)) errors.add(new Api.FieldError("config." + name, "is not allowed for " + type));
    long codFee = money(config.path("cod_fee"), "config.cod_fee", errors);
    long fee = "WEIGHT".equals(type) ? 0 : money(config.path("fee"), "config.fee", errors);
    List<String> provinces = List.of();
    if ("PROVINCE".equals(type) || config.has("provinces")) provinces = provinces(config.path("provinces"), errors);
    List<Tier> tiers = "WEIGHT".equals(type) ? tiers(config.path("tiers"), errors) : List.of();
    if (!errors.isEmpty()) throw invalid(errors);
    return new RuleConfig(type, provinces, tiers, fee, codFee);
  }

  private static List<String> provinces(JsonNode node, List<Api.FieldError> errors) {
    if (!node.isArray() || node.isEmpty() || node.size() > 100) { errors.add(new Api.FieldError("config.provinces", "must list 1..100 province codes")); return List.of(); }
    var seen = new HashSet<String>();
    var result = new ArrayList<String>();
    for (JsonNode p : node) {
      if (!p.isString() || !PROVINCE.matcher(p.asString()).matches() || !seen.add(p.asString()))
        errors.add(new Api.FieldError("config.provinces", "codes must be distinct, 1..32 of A-Z a-z 0-9 _ -"));
      else result.add(p.asString());
    }
    return result;
  }

  /** Strictly increasing max_weight_grams; only the last tier may be null (no upper bound). */
  private static List<Tier> tiers(JsonNode node, List<Api.FieldError> errors) {
    if (!node.isArray() || node.isEmpty() || node.size() > 20) { errors.add(new Api.FieldError("config.tiers", "must have 1..20 tiers")); return List.of(); }
    var result = new ArrayList<Tier>();
    Integer previous = null;
    for (int i = 0; i < node.size(); i++) {
      JsonNode t = node.get(i);
      String at = "config.tiers[" + i + "]";
      if (!t.isObject()) { errors.add(new Api.FieldError(at, "must be an object")); continue; }
      for (String name : t.propertyNames()) if (!Set.of("max_weight_grams", "fee").contains(name)) errors.add(new Api.FieldError(at + "." + name, "is not allowed"));
      JsonNode max = t.path("max_weight_grams");
      Integer limit = null;
      if (max.isMissingNode()) {
        errors.add(new Api.FieldError(at + ".max_weight_grams", "is required (null = no upper bound)"));
      } else if (max.isNull()) {
        if (i != node.size() - 1) errors.add(new Api.FieldError(at + ".max_weight_grams", "only the last tier may be unbounded"));
      } else if (!max.isIntegralNumber() || !max.canConvertToInt() || max.intValue() < 1) {
        errors.add(new Api.FieldError(at + ".max_weight_grams", "must be a positive integer or null"));
      } else {
        limit = max.intValue();
        if (previous != null && limit <= previous) errors.add(new Api.FieldError(at + ".max_weight_grams", "must increase"));
        previous = limit;
      }
      result.add(new Tier(limit, money(t.path("fee"), at + ".fee", errors)));
    }
    return result;
  }

  private static long money(JsonNode node, String field, List<Api.FieldError> errors) {
    if (node.isIntegralNumber() && node.canConvertToLong() && node.longValue() >= 0 && node.longValue() <= MAX_MONEY) return node.longValue();
    errors.add(new Api.FieldError(field, "must be an integer VND 0.." + MAX_MONEY));
    return 0;
  }

  static Api.Problem invalid(List<Api.FieldError> errors) {
    return new Api.Problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "INVALID_FIELDS", errors);
  }
}
