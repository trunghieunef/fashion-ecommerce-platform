package vn.fashion.user.web;

import io.micrometer.tracing.Tracer;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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
}
