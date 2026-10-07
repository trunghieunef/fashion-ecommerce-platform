package vn.fashion.catalog.admin;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import vn.fashion.catalog.web.Api;
import vn.fashion.platform.outbox.OutboxEvent;
import vn.fashion.platform.outbox.OutboxRepository;

@Service
public class VariantAdminService {
  public record Input(String sku, String size, String color, Long priceOverride, Integer weightGrams) { }
  public record Update(Long priceOverride, Integer weightGrams, String status, Long expectedVersion) { }
  private final JdbcClient jdbc;
  private final ProductAdminService products;
  private final AdminCommands commands;
  private final AuditLog audit;
  private final OutboxRepository outbox;
  private final ObjectMapper json;
  public VariantAdminService(JdbcClient jdbc, ProductAdminService products, AdminCommands commands, AuditLog audit, OutboxRepository outbox, ObjectMapper json) {
    this.jdbc = jdbc; this.products = products; this.commands = commands; this.audit = audit; this.outbox = outbox; this.json = json;
  }
  private static void priceWeight(java.util.List<Api.FieldError> errors, Long price, Integer weight) {
    if (price != null && price < 0) errors.add(new Api.FieldError("price_override", "must be nonnegative integer VND"));
    if (weight == null || weight <= 0) errors.add(new Api.FieldError("weight_grams", "must be a positive integer"));
  }
  Input normalize(Input input) {
    var r = new Input(TaxonomyService.strip(input.sku()), TaxonomyService.strip(input.size()), TaxonomyService.strip(input.color()), input.priceOverride(), input.weightGrams());
    var errors = new ArrayList<Api.FieldError>();
    if (r.sku() == null || r.sku().length() > 64 || !r.sku().matches("^[A-Za-z0-9][A-Za-z0-9._-]*$")) errors.add(new Api.FieldError("sku", "must be 1..64 SKU characters"));
    TaxonomyService.text(errors, "size", r.size(), 50); TaxonomyService.text(errors, "color", r.color(), 50);
    priceWeight(errors, r.priceOverride(), r.weightGrams()); TaxonomyService.valid(errors); return r;
  }
  AdminCommands.Replay create(UUID actor, UUID productId, String key, Input r, String traceId, UUID requestId) {
    return commands.create(actor, "catalog.variant.create:" + productId, key, r, () -> {
      products.lockForUpdate(productId); UUID id = UUID.randomUUID();
      var v = jdbc.sql("""
          insert into product_variants(id,product_id,sku,size,color,price_override,weight_grams)
          values (:id,:product,:sku,:size,:color,:price,:weight) returning *
          """).param("id", id).param("product", productId).param("sku", r.sku()).param("size", r.size()).param("color", r.color())
          .param("price", r.priceOverride()).param("weight", r.weightGrams()).query(Variant.class).single();
      long version = jdbc.sql("update products set version=version+1,updated_at=now() where id=:id returning version")
          .param("id", productId).query(Long.class).single();
      var intent = new OutboxEvent(UUID.randomUUID(), "product", productId.toString(), version, "VARIANT_CREATED", 1,
          "catalog.events", productId.toString(), traceId, json.writeValueAsString(Map.of("product_id", productId, "variant_id", id, "sku", v.sku(), "version", 0)));
      outbox.append(intent);
      audit.record(actor, "catalog.variant.create", "variant", id, null, null, v, requestId);
      return new AdminCommands.Result(id, 201, v);
    });
  }
  Variant update(UUID actor, UUID id, Update r, UUID requestId) {
    var errors = new ArrayList<Api.FieldError>(); priceWeight(errors, r.priceOverride(), r.weightGrams());
    TaxonomyService.status(errors, r.status()); TaxonomyService.valid(errors);
    return commands.update(() -> {
      var before = jdbc.sql("select * from product_variants where id=:id for update").param("id", id).query(Variant.class).optional()
          .orElseThrow(() -> TaxonomyService.notFound("VARIANT_NOT_FOUND"));
      AdminCommands.requireVersion(r.expectedVersion(), before.version());
      var result = jdbc.sql("update product_variants set price_override=:price,weight_grams=:weight,status=:status,version=version+1,updated_at=now() where id=:id returning *")
          .param("id", id).param("price", r.priceOverride()).param("weight", r.weightGrams()).param("status", r.status()).query(Variant.class).single();
      audit.record(actor, "catalog.variant.update", "variant", id, null, before, result, requestId); return result;
    });
  }
}
