package vn.fashion.catalog.admin;

import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;

public final class HtmlSanitizer {
  private static final Safelist ALLOWED = Safelist.basic().addTags("h2", "h3")
      .removeProtocols("a", "href", "ftp", "mailto").addEnforcedAttribute("a", "rel", "nofollow noopener");
  private HtmlSanitizer() { }
  public static String clean(String html) { return html == null ? "" : Jsoup.clean(html, ALLOWED); }
}
