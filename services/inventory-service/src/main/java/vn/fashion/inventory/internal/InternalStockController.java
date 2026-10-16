package vn.fashion.inventory.internal;

import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import vn.fashion.inventory.admin.StockQueries;
import vn.fashion.inventory.stock.Sku;
import vn.fashion.inventory.web.Api;
import vn.fashion.platform.security.*;

@RestController
public class InternalStockController {
  private static final Set<String> ALLOWED_CALLERS=Set.of("catalog-service","cart-service");
  private final ServiceTokenVerifier callers;
  private final StockQueries stocks;
  private final Tracer tracer;
  public InternalStockController(@Value("${fashion.inventory.internal-callers}") String keys,StockQueries stocks,Tracer tracer) {
    callers=new ServiceTokenVerifier("inventory-service",keys.isBlank()?Map.of():AccessTokenVerifier.parseKeys(keys),Clock.systemUTC());
    this.stocks=stocks;this.tracer=tracer;
  }
  @GetMapping("/internal/api/v1/inventory/skus/{sku}")
  public ResponseEntity<?> get(@RequestHeader(name="Authorization",required=false) String authorization,
      @PathVariable String sku,HttpServletResponse response) {
    response.setHeader("Cache-Control","no-store");
    String token=authorization!=null && authorization.regionMatches(true,0,"Bearer ",0,7)?authorization.substring(7).strip():null;
    String caller=callers.verify(token).orElseThrow(()->new Api.Problem(HttpStatus.UNAUTHORIZED,"UNAUTHORIZED","INVALID_SERVICE_TOKEN",List.of()));
    if(!ALLOWED_CALLERS.contains(caller)) throw new Api.Problem(HttpStatus.FORBIDDEN,"FORBIDDEN","CALLER_NOT_ALLOWED",List.of());
    var row=stocks.stock(Sku.canonical(sku));
    return Api.ok(HttpStatus.OK,Map.of("sku",row.sku(),"variant_id",row.variantId(),"available",row.available(),
        "version",row.version(),"observed_at",row.updatedAt().toString()),tracer);
  }
}
