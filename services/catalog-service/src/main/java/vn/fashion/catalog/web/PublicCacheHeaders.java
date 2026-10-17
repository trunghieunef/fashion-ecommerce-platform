package vn.fashion.catalog.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** CAT-02b spec section 6: public catalog JSON is no-store unless a handler allows caching its 200. */
@Component
public class PublicCacheHeaders extends OncePerRequestFilter {
  public static final int LIST = 30, DETAIL = 5, STATIC = 60;

  @Override protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getServletPath();
    return !path.startsWith("/api/v1/catalog/") || path.startsWith("/api/v1/catalog/images/");
  }

  @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                           FilterChain chain) throws ServletException, IOException {
    // Binding, validation and not-found errors happen after this and must stay uncached.
    response.setHeader("Cache-Control", "no-store");
    chain.doFilter(request, response);
  }

  public static void allow(HttpServletResponse response, int maxAgeSeconds) {
    response.setHeader("Cache-Control", "public, max-age=" + maxAgeSeconds);
  }
  public static void varyLanguage(HttpServletResponse response) { response.setHeader("Vary", "Accept-Language"); }
}
