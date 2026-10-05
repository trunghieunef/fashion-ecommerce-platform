package vn.fashion.user.web;

import io.micrometer.tracing.Tracer;
import java.util.List;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {
  private final Tracer tracer;

  public ApiExceptionHandler(Tracer tracer) {
    this.tracer = tracer;
  }

  @ExceptionHandler(Api.Problem.class)
  ResponseEntity<Api.Error> problem(Api.Problem problem) {
    return Api.error(problem.status(), problem.code(), problem.getMessage(), problem.errors(), tracer);
  }

  /** Redis or PostgreSQL unreachable: the transaction rolled back, so nothing happened (03 section 1.2). */
  @ExceptionHandler(DataAccessResourceFailureException.class)
  ResponseEntity<Api.Error> unavailable() {
    return Api.error(HttpStatus.SERVICE_UNAVAILABLE, "TEMPORARILY_UNAVAILABLE", "DEPENDENCY_UNAVAILABLE", List.of(),
        tracer);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<Api.Error> unreadable() {
    return Api.error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "MALFORMED_JSON", List.of(), tracer);
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ResponseEntity<Api.Error> typeMismatch(MethodArgumentTypeMismatchException e) {
    return Api.error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "INVALID_PARAMETER",
        List.of(new Api.FieldError(e.getName(), "has an invalid format")), tracer);
  }
}
