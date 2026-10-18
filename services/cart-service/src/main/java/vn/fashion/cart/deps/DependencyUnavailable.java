package vn.fashion.cart.deps;

/** Catalog/inventory could not answer usably (network, timeout, non-2xx, wrong shape); never retried here. */
public class DependencyUnavailable extends RuntimeException {
  public DependencyUnavailable(String dependency) { super(dependency + " unavailable", null, false, false); }
}
