package vn.fashion.inventory.internal;

import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import vn.fashion.inventory.web.Api;
import vn.fashion.platform.security.*;

/** ADR-19 service token check shared by internal routes: token first, then the per-route caller allowlist. */
@Component
public class InternalCallers {
  private final ServiceTokenVerifier verifier;
  public InternalCallers(@Value("${fashion.inventory.internal-callers}") String keys) {
    verifier=new ServiceTokenVerifier("inventory-service",keys.isBlank()?Map.of():AccessTokenVerifier.parseKeys(keys),Clock.systemUTC());
  }
  public String require(String authorization,Set<String> allowed) {
    String token=authorization!=null && authorization.regionMatches(true,0,"Bearer ",0,7)?authorization.substring(7).strip():null;
    String caller=verifier.verify(token).orElseThrow(()->new Api.Problem(HttpStatus.UNAUTHORIZED,"UNAUTHORIZED","INVALID_SERVICE_TOKEN",List.of()));
    if(!allowed.contains(caller)) throw new Api.Problem(HttpStatus.FORBIDDEN,"FORBIDDEN","CALLER_NOT_ALLOWED",List.of());
    return caller;
  }
}
