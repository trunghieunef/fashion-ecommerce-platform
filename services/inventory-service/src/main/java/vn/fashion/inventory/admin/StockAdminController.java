package vn.fashion.inventory.admin;

import io.micrometer.tracing.Tracer;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import vn.fashion.inventory.web.*;

@RestController
@RequestMapping("/admin/api/v1/inventory")
public class StockAdminController {
  private final AdminAuth auth;
  private final StockAdminService service;
  private final StockQueries queries;
  private final Tracer tracer;
  public StockAdminController(AdminAuth auth,StockAdminService service,StockQueries queries,Tracer tracer) {
    this.auth=auth;this.service=service;this.queries=queries;this.tracer=tracer;
  }
  @GetMapping("/skus") public ResponseEntity<?> list(
      @RequestHeader(name="Authorization",required=false) String authorization,
      @RequestParam(name="sku_prefix",required=false) String prefix,@RequestParam(required=false) String limit,@RequestParam(required=false) String cursor) {
    auth.require(authorization,"inventory.read");return Api.ok(HttpStatus.OK,queries.list(prefix,limit,cursor),tracer);
  }
  @GetMapping("/skus/{sku}/transactions") public ResponseEntity<?> transactions(
      @RequestHeader(name="Authorization",required=false) String authorization,@PathVariable String sku,
      @RequestParam(required=false) String limit,@RequestParam(required=false) String cursor) {
    auth.require(authorization,"inventory.read");return Api.ok(HttpStatus.OK,queries.transactions(sku,limit,cursor),tracer);
  }
  @PostMapping("/adjust") public ResponseEntity<?> adjust(
      @RequestHeader(name="Authorization",required=false) String authorization,
      @RequestHeader(name="Idempotency-Key",required=false) String key,@RequestBody(required=false) JsonNode body) {
    var actor=auth.require(authorization,"inventory.adjust");
    if(body==null || !body.isObject()) throw StockAdminService.invalid("body","must be an object");
    var fields=body.propertyNames().iterator();
    while(fields.hasNext()) { String f=fields.next();if(!Set.of("sku","delta","reason").contains(f)) throw StockAdminService.invalid(f,"unknown field"); }
    if(!body.path("sku").isString()) throw StockAdminService.invalid("sku","must be a string");
    if(!body.path("delta").isIntegralNumber() || !body.path("delta").canConvertToInt()) throw StockAdminService.invalid("delta","must be an integer");
    if(!body.path("reason").isString()) throw StockAdminService.invalid("reason","must be a string");
    var meta=Api.metadata(tracer);
    var result=service.adjust(actor,key,new StockAdminService.AdjustRequest(body.path("sku").asText(),body.path("delta").intValue(),body.path("reason").asText()),meta.traceId());
    return Api.ok(HttpStatus.valueOf(result.status()),result.data(),meta);
  }
}
