package vn.fashion.inventory.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Internal stock/reservation data and errors are never cached, including errors raised before a controller runs. */
@Component
public class InternalNoStoreFilter extends OncePerRequestFilter {
  @Override protected boolean shouldNotFilter(HttpServletRequest request) { return !request.getServletPath().startsWith("/internal/"); }
  @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
      throws ServletException,IOException {
    response.setHeader("Cache-Control","no-store");
    chain.doFilter(request,response);
  }
}
