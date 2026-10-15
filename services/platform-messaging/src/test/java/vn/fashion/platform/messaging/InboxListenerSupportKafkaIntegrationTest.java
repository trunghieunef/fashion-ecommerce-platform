package vn.fashion.platform.messaging;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.*;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;

class InboxListenerSupportKafkaIntegrationTest extends MessagingTestSupport {
  final AtomicInteger attempts = new AtomicInteger();
  final AtomicInteger earlyAcks = new AtomicInteger();
  UUID permanentId;
  int failures;
  @Override void registerListener() { context.registerBean(Listener.class, Listener::new); }

  class Listener {
    @KafkaListener(id="test-listener", topics="${test.topic}", groupId="${test.group}")
    public void receive(ConsumerRecord<String, byte[]> record, Acknowledgment ack) {
      new InboxListenerSupport(inbox, json, "test-consumer").handle(record, () -> {
        var env = EventEnvelope.parse(record.value(), json);
        if (env.eventType().equals("VARIANT_CREATED")) {
          var independent = JdbcClient.create(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),
              POSTGRES.getUsername(), POSTGRES.getPassword()));
          if (independent.sql("select count(*) from effects where event_id=:id").param("id", env.eventId())
              .query(Integer.class).single() != 1) {
            earlyAcks.incrementAndGet(); throw new IllegalStateException("ack before committed effect");
          }
        }
        ack.acknowledge();
      }, Map.of("VARIANT_CREATED", env -> {
        if (env.eventId().equals(permanentId)) throw new PermanentEventException("invalid event data");
        if (attempts.incrementAndGet() <= failures) throw new IllegalStateException("temporary dependency failure");
        return jdbc.sql("insert into effects(event_id) values (:id)").param("id", env.eventId()).update();
      }));
    }
  }

  void send(UUID id, String type, int partition) throws Exception {
    kafka.send(new ProducerRecord<>(topic, partition, "product-a", envelope(id, type)))
        .get(5, java.util.concurrent.TimeUnit.SECONDS);
  }

  @Test void appliesOnceForDuplicateEventId() throws Exception {
    var id=UUID.randomUUID(); send(id,"VARIANT_CREATED",0); send(id,"VARIANT_CREATED",0);
    await(() -> committed(0) >= 2);
    assertThat(effects()).isEqualTo(1); assertThat(attempts.get()).isEqualTo(1);
    assertThat(jdbc.sql("select count(*) from processed_events").query(Integer.class).single()).isEqualTo(1);
  }

  @Test @SuppressWarnings("unchecked") void permanentFailureGoesToDlt() throws Exception {
    permanentId=UUID.randomUUID(); send(permanentId,"VARIANT_CREATED",2);
    var raw=(KafkaTemplate<String,byte[]>)context.getBean("dltKafkaTemplate");
    byte[] corrupt = {(byte)0xff, 0x00};
    var record=new ProducerRecord<String,byte[]>(topic,2,"product-a",corrupt);
    record.headers().add("probe", "preserved".getBytes(UTF_8));
    raw.send(record).get(5,java.util.concurrent.TimeUnit.SECONDS);
    send(UUID.randomUUID(),"VARIANT_CREATED",2);
    var dlt=consume(topic+".DLT",2);
    assertThat(dlt).allSatisfy(r -> assertThat(r.partition()).isEqualTo(2));
    assertThat(dlt.get(0).value()).isEqualTo(envelope(permanentId,"VARIANT_CREATED").getBytes(UTF_8));
    assertThat(dlt.get(1).value()).isEqualTo(corrupt);
    assertThat(new String(dlt.get(1).headers().lastHeader("probe").value(),UTF_8)).isEqualTo("preserved");
    await(() -> effects()==1 && committed(2)>=3);
    assertThat(meters.get("messaging_dlt_total").tag("topic",topic).counter().count()).isEqualTo(2);
  }

  @Test void transientFailureIsRetriedThenApplied() throws Exception {
    failures=2; send(UUID.randomUUID(),"VARIANT_CREATED",0);
    await(() -> earlyAcks.get()>0 || (effects()==1 && committed(0)>=1));
    assertThat(earlyAcks.get()).isZero(); assertThat(attempts.get()).isEqualTo(3);
    assertThat(effects()).isEqualTo(1);
    assertThat(jdbc.sql("select count(*) from processed_events").query(Integer.class).single()).isEqualTo(1);
    assertThat(meters.find("messaging_dlt_total").tag("topic",topic).counter()).isNull();
  }

  @Test @SuppressWarnings("unchecked") void nullEventIdHeaderDoesNotBlockAfterDlt() throws Exception {
    var raw = (KafkaTemplate<String, byte[]>) context.getBean("dltKafkaTemplate");
    byte[] corrupt = {(byte) 0xff, 0x00};
    var record = new ProducerRecord<String, byte[]>(topic, 1, "product-a", corrupt);
    record.headers().add("event_id", null);
    raw.send(record).get(5, java.util.concurrent.TimeUnit.SECONDS);
    send(UUID.randomUUID(), "VARIANT_CREATED", 1);

    await(() -> effects() == 1 && committed(1) >= 2);
    var dlt = consume(topic + ".DLT", 1).getFirst();
    assertThat(dlt.value()).isEqualTo(corrupt);
    assertThat(dlt.headers().lastHeader("event_id")).isNotNull();
    assertThat(dlt.headers().lastHeader("event_id").value()).isNull();
    assertThat(meters.get("messaging_dlt_total").tag("topic", topic).counter().count()).isEqualTo(1);
  }

  @Test void unknownEventTypeIsAckedWithoutInbox() throws Exception {
    send(UUID.randomUUID(),"CATALOG_CHANGED",0); await(() -> committed(0)>=1);
    send(UUID.randomUUID(),"UNREGISTERED_EVENT",0); await(() -> committed(0)>=2);
    assertThat(effects()).isZero();
    assertThat(jdbc.sql("select count(*) from processed_events").query(Integer.class).single()).isZero();
  }
}
