package vn.fashion.platform.messaging;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.core.log.LogAccessor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.backoff.ExponentialBackOff;
import vn.fashion.platform.outbox.OutboxRepository;

@AutoConfiguration
@EnableKafka
public class MessagingAutoConfiguration {
  private static final LogAccessor LOG = new LogAccessor(MessagingAutoConfiguration.class);

  private static Map<String, Object> producerProperties(String bootstrap, Class<?> valueSerializer) {
    return Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
        ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
        ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, valueSerializer,
        ProducerConfig.ACKS_CONFIG, "all", ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
  }
  @Bean @ConditionalOnMissingBean(name="kafkaProducerFactory")
  DefaultKafkaProducerFactory<String, String> kafkaProducerFactory(
      @Value("${spring.kafka.bootstrap-servers:localhost:19092}") String bootstrap) {
    return new DefaultKafkaProducerFactory<>(producerProperties(bootstrap, StringSerializer.class));
  }
  @Bean @ConditionalOnMissingBean(name="kafkaTemplate")
  KafkaTemplate<String, String> kafkaTemplate(
      @Qualifier("kafkaProducerFactory") DefaultKafkaProducerFactory<String, String> factory) {
    var kafka = new KafkaTemplate<>(factory);
    kafka.setProducerListener(null); // Default listener logs event payloads on send errors.
    return kafka;
  }
  @Bean DefaultKafkaProducerFactory<String, byte[]> messagingDltProducerFactory(
      @Value("${spring.kafka.bootstrap-servers:localhost:19092}") String bootstrap) {
    return new DefaultKafkaProducerFactory<>(producerProperties(bootstrap, ByteArraySerializer.class));
  }
  @Bean KafkaTemplate<String, byte[]> dltKafkaTemplate(
      @Qualifier("messagingDltProducerFactory") DefaultKafkaProducerFactory<String, byte[]> factory) {
    var kafka = new KafkaTemplate<>(factory); kafka.setProducerListener(null); return kafka;
  }

  @Bean @ConditionalOnMissingBean(DefaultErrorHandler.class)
  DefaultErrorHandler kafkaErrorHandler(@Qualifier("dltKafkaTemplate") KafkaTemplate<String, byte[]> kafka,
                                        MeterRegistry meters) {
    var dlt = new DeadLetterPublishingRecoverer(kafka,
        (record, error) -> new TopicPartition(record.topic() + ".DLT", record.partition()));
    dlt.setFailIfSendResultIsError(true);
    dlt.setWaitForSendResultTimeout(Duration.ofSeconds(5));
    var backoff = new ExponentialBackOff(1000, 2.0);
    backoff.setMaxInterval(30_000); backoff.setMaxElapsedTime(Long.MAX_VALUE);
    var handler = new DefaultErrorHandler((record, error) -> {
      dlt.accept(record, null, error);
      meters.counter("messaging_dlt_total", "topic", record.topic()).increment();
      String eventId = "unknown";
      var header = record.headers().lastHeader("event_id");
      if (header != null && header.value() != null) {
        try { eventId = UUID.fromString(new String(header.value(), java.nio.charset.StandardCharsets.UTF_8)).toString(); }
        catch (IllegalArgumentException ignored) { }
      }
      LOG.error("Event moved to DLT; event_id=" + eventId);
    }, backoff);
    handler.addNotRetryableExceptions(PermanentEventException.class);
    return handler;
  }

  @Bean @ConditionalOnMissingBean(name="kafkaListenerContainerFactory")
  ConcurrentKafkaListenerContainerFactory<String, byte[]> kafkaListenerContainerFactory(
      @Value("${spring.kafka.bootstrap-servers:localhost:19092}") String bootstrap, DefaultErrorHandler errorHandler) {
    var props = new HashMap<String, Object>();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    var factory = new ConcurrentKafkaListenerContainerFactory<String, byte[]>();
    factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(props));
    factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
    factory.setCommonErrorHandler(errorHandler);
    return factory;
  }

  @Configuration(proxyBeanMethods=false)
  @EnableScheduling
  @ConditionalOnProperty(name="fashion.messaging.relay.enabled", havingValue="true")
  static class RelayConfiguration {
    @Bean OutboxRelay outboxRelay(OutboxRepository outbox,
        @Qualifier("kafkaTemplate") KafkaTemplate<String, String> kafka, MeterRegistry meters,
        @Value("${fashion.messaging.relay.send-timeout:5s}") Duration timeout) {
      var relay = new OutboxRelay(outbox, kafka, timeout); relay.metrics(meters); return relay;
    }
  }

  @Bean MeterBinder outboxMetrics(JdbcClient jdbc) {
    return registry -> {
      Gauge.builder("outbox_pending", jdbc,
          db -> db.sql("select count(*) from outbox_events where status <> 'SENT'").query(Double.class).single())
          .register(registry);
      Gauge.builder("outbox_oldest_pending_age_seconds", jdbc, db -> db.sql("""
          select coalesce(extract(epoch from now()-min(created_at)),0)
          from outbox_events where status <> 'SENT'
          """).query(Double.class).single()).register(registry);
    };
  }
  @Bean(name="kafka") @ConditionalOnMissingBean(name="kafka")
  KafkaHealthIndicator kafkaHealth(@Value("${spring.kafka.bootstrap-servers:localhost:19092}") String bootstrap) {
    return new KafkaHealthIndicator(bootstrap);
  }
}
