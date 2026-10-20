package vn.fashion.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;
import vn.fashion.order.quotes.Quote;
import vn.fashion.order.quotes.QuoteTokens;
import vn.fashion.order.web.Api;

class QuoteTokensTest {
  static final String SECRET = Base64.getEncoder().encodeToString("order-quote-test-secret-32-bytes!".getBytes());
  final QuoteTokens tokens = new QuoteTokens(JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build(),
      "q1", SECRET);

  static Quote sample() {
    return new Quote("user:" + UUID.randomUUID(), UUID.randomUUID(), 4, "COD",
        new Quote.Address("Synthetic Person", "0912345678", "79", "26734", "1 Synthetic St"),
        new Quote.Contact("0912345678", "a@example.test"), "note", "vi",
        List.of(new Quote.Line(UUID.randomUUID(), UUID.randomUUID(), 1, 2, 150000, 300000, 7, "TEE-M", "Ao thun", "M", "Den", null)),
        new Quote.Shipping(UUID.randomUUID(), "SELF", 900, 30000, 10000, Instant.parse("2026-10-20T03:15:00Z")),
        new Quote.Totals(300000, 0, 30000, 0, 10000, 340000, "VND"), Instant.parse("2026-10-20T03:02:00Z"));
  }

  @Test void aTokenRoundTripsEveryField() {
    var quote = sample();
    assertThat(tokens.verify(tokens.sign(quote))).isEqualTo(quote);
  }

  @Test void anyChangeOrAnotherKeyIsInvalid() {
    String token = tokens.sign(sample());
    String payload = token.substring(0, token.indexOf('.'));
    String tampered = (payload.charAt(5) == 'A' ? payload.substring(0, 5) + "B" : payload.substring(0, 5) + "A")
        + payload.substring(6) + token.substring(token.indexOf('.'));
    assertThatThrownBy(() -> tokens.verify(tampered)).isInstanceOf(Api.Problem.class).hasMessage("INVALID_QUOTE");
    var other = new QuoteTokens(JsonMapper.builder().build(), "q2",
        Base64.getEncoder().encodeToString("another-secret-of-thirty-two-byte".getBytes()));
    assertThatThrownBy(() -> tokens.verify(other.sign(sample()))).isInstanceOf(Api.Problem.class).hasMessage("INVALID_QUOTE");
    assertThatThrownBy(() -> tokens.verify("no-dot")).isInstanceOf(Api.Problem.class).hasMessage("INVALID_QUOTE");
    assertThatThrownBy(() -> tokens.verify(null)).isInstanceOf(Api.Problem.class).hasMessage("INVALID_QUOTE");
  }
}
