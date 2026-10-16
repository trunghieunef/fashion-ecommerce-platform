package vn.fashion.inventory.internal;

import io.micrometer.tracing.Tracer;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import vn.fashion.inventory.admin.StockQueries;
import vn.fashion.inventory.stock.Sku;
import vn.fashion.inventory.web.Api;

@RestController
public class InternalStockController {
  private static final Set<String> ALLOWED_CALLERS=Set.of("catalog-service","cart-service");
  private final InternalCallers callers;
  private final StockQueries stocks;
  private final Tracer tracer;
  public InternalStockController(InternalCallers callers,StockQueries stocks,Tracer tracer) {
    this.callers=callers;this.stocks=stocks;this.tracer=tracer;
  }
  @GetMapping("/internal/api/v1/inventory/skus/{sku}")
  public ResponseEntity<?> get(@RequestHeader(name="Authorization",required=false) String authorization,
      @PathVariable String sku) {
    callers.require(authorization,ALLOWED_CALLERS);
    var row=stocks.stock(Sku.canonical(sku));
    return Api.ok(HttpStatus.OK,Map.of("sku",row.sku(),"variant_id",row.variantId(),"available",row.available(),
        "version",row.version(),"observed_at",row.updatedAt().toString()),tracer);
  }
}
