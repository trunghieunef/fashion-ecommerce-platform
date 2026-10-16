package vn.fashion.inventory.reservation;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Gauge always exists; the scheduled trigger exists only when enabled. Tests call expireDue directly. */
@Configuration
public class ReservationSweep {
  @Bean Gauge overdueReservations(MeterRegistry meters,ReservationRepository reservations) {
    return Gauge.builder("inventory.reservations.overdue",reservations,r->r.overdueCount())
        .description("ACTIVE reservations past expires_at not yet swept").register(meters);
  }
  @Configuration @EnableScheduling @ConditionalOnBooleanProperty("fashion.inventory.reservation-sweep.enabled")
  static class Trigger {
    private final ReservationService service;
    private final int batch;
    Trigger(ReservationService service,@Value("${fashion.inventory.reservation-sweep.batch:100}") int batch) {
      this.service=service;this.batch=batch;
    }
    @Scheduled(fixedDelayString="${fashion.inventory.reservation-sweep.interval:5s}") void sweep() { service.expireDue(batch); }
  }
}
