package vn.fashion.platform.messaging;

import io.micrometer.tracing.Tracer;
import java.util.function.Supplier;

/** TASK:PLT-05: W3C traceparent of the current span, for OutboxRepository(jdbc, supplier). */
public final class TraceParents {
  private TraceParents() { }

  public static Supplier<String> current(Tracer tracer) {
    return () -> {
      var span = tracer.currentSpan();
      if (span == null) return null;
      var context = span.context();
      return "00-" + context.traceId() + "-" + context.spanId() + "-" + (Boolean.TRUE.equals(context.sampled()) ? "01" : "00");
    };
  }
}
