package vn.fashion.catalog.admin;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class HtmlSanitizerTest {
  @Test void stripsScriptsHandlersAndJavascriptLinks() {
    assertThat(HtmlSanitizer.clean("<p onclick=x>a<script>b</script><img src=x onerror=y><a href=\"javascript:z\">c</a></p>"))
        .doesNotContain("script", "onclick", "onerror", "javascript", "img");
  }
  @Test void keepsAllowedFormattingAndForcesRel() {
    assertThat(HtmlSanitizer.clean("<h2>T</h2><strong>s</strong><a href=\"https://e.test\">l</a>"))
        .contains("<h2>T</h2>", "<strong>s</strong>", "href=\"https://e.test\"", "rel=\"nofollow noopener\"");
    assertThat(HtmlSanitizer.clean(null)).isEmpty();
  }
}
