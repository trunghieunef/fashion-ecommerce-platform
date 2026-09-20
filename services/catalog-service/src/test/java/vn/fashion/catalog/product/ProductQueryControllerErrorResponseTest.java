package vn.fashion.catalog.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;

class ProductQueryControllerErrorResponseTest {
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void returnsFieldLevelValidationErrorWithCorrelationHeader() {
    var controller = new ProductQueryController(null);
    var response = controller.invalidRequest(
        new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_LIMIT"));
    var body = objectMapper.valueToTree(response.getBody());

    assertThat(body.path("code").asText()).isEqualTo("VALIDATION_ERROR");
    assertThat(body.path("message").asText()).isEqualTo("INVALID_LIMIT");
    assertThat(body.path("errors").isArray()).isTrue();
    assertThat(body.path("errors")).hasSize(1);
    assertThat(body.path("errors").get(0).path("field").asText()).isEqualTo("limit");
    assertThat(body.path("errors").get(0).path("message").asText())
        .isEqualTo("must be between 1 and 100");
    assertThat(body.path("errors").get(0).has("rejected_value")).isTrue();
    assertThat(response.getHeaders().getFirst("X-Correlation-Id"))
        .isEqualTo(body.path("metadata").path("trace_id").asText());
  }
}
