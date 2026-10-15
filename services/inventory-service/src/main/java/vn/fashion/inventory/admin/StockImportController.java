package vn.fashion.inventory.admin;

import io.micrometer.tracing.Tracer;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import vn.fashion.inventory.web.*;

@RestController
public class StockImportController {
  private final AdminAuth auth;
  private final StockAdminService service;
  private final Tracer tracer;
  public StockImportController(AdminAuth auth,StockAdminService service,Tracer tracer) { this.auth=auth;this.service=service;this.tracer=tracer; }
  @PostMapping(value="/admin/api/v1/inventory/import",consumes="multipart/form-data")
  public ResponseEntity<?> upload(@RequestHeader(name="Authorization",required=false) String authorization,
      @RequestHeader(name="Idempotency-Key",required=false) String key,
      @RequestParam(name="file",required=false) MultipartFile file,@RequestParam(required=false) String reason) throws IOException {
    var actor=auth.require(authorization,"inventory.adjust");
    if(file==null) throw StockAdminService.invalid("file","is required");
    var meta=Api.metadata(tracer);var result=service.importInbound(actor,key,file.getBytes(),reason,meta.traceId());
    return Api.ok(HttpStatus.valueOf(result.status()),result.data(),meta);
  }
}
