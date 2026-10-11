package vn.fashion.catalog.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class VariantQuoteNoStoreFilter extends OncePerRequestFilter {
  @Override protected boolean shouldNotFilter(HttpServletRequest request) {
    return !VariantQuoteController.PATH.equals(request.getServletPath());
  }

  @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                           FilterChain chain) throws ServletException, IOException {
    // Binding and HTTP errors can occur before the controller runs.
    response.setHeader("Cache-Control", "no-store");
    chain.doFilter(request, response);
  }
}
