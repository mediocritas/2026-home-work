package company.vk.edu.distrib.compute.mediocritas.audit;

import company.vk.edu.distrib.compute.AuditEvent;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class PushkinaAuditProducer {

    private static final Logger log = LoggerFactory.getLogger(PushkinaAuditProducer.class);
    private static final String AUDIT_TOPIC = "audit";

    private final AtomicReference<KafkaProducer<String, String>> producer = new AtomicReference<>();
    private final AtomicReference<String> bootstrapServers = new AtomicReference<>();
    private final AtomicBoolean async = new AtomicBoolean();

    public void setBootstrapServers(String bootstrapServers) {
        this.bootstrapServers.set(bootstrapServers);
    }

    public void setAsync(boolean enabled) {
        async.set(enabled);
    }

    public void start() {
        String servers = bootstrapServers.get();
        if (servers == null || servers.isBlank()) {
            return;
        }

        KafkaProducer<String, String> newProducer = new KafkaProducer<>(producerProperties(servers));
        closeProducer(producer.getAndSet(newProducer));
    }

    public void send(String method, String id, long timestamp) throws IOException {
        KafkaProducer<String, String> kafkaProducer = producer.get();
        if (kafkaProducer == null) {
            return;
        }

        AuditEvent event = new AuditEvent(method, id, timestamp);
        ProducerRecord<String, String> record = new ProducerRecord<>(AUDIT_TOPIC, id, AuditCodec.encode(event));
        if (async.get()) {
            kafkaProducer.send(record, (metadata, exception) -> {
                if (exception != null) {
                    log.error("Failed to send audit event", exception);
                }
            });
            return;
        }

        try {
            kafkaProducer.send(record).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while sending audit event", e);
        } catch (ExecutionException e) {
            throw new IOException("Failed to send audit event", e);
        }
    }

    public void stop() {
        closeProducer(producer.getAndSet(null));
    }

    private Properties producerProperties(String servers) {
        Properties properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, servers);
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        properties.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, Duration.ofSeconds(1).toMillis());
        properties.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) Duration.ofSeconds(1).toMillis());
        properties.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, (int) Duration.ofSeconds(3).toMillis());
        return properties;
    }

    private void closeProducer(KafkaProducer<String, String> kafkaProducer) {
        if (kafkaProducer != null) {
            kafkaProducer.flush();
            kafkaProducer.close(Duration.ofSeconds(1));
        }
    }
}
