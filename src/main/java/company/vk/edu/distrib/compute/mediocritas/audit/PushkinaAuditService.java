package company.vk.edu.distrib.compute.mediocritas.audit;

import company.vk.edu.distrib.compute.AuditEvent;
import company.vk.edu.distrib.compute.AuditService;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class PushkinaAuditService implements AuditService {

    private static final Logger log = LoggerFactory.getLogger(PushkinaAuditService.class);
    private static final String AUDIT_TOPIC = "audit";

    private final String bootstrapServers;
    private final String consumerGroupId;
    private final Path auditLogPath;
    private final List<AuditEvent> events = new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicReference<KafkaConsumer<String, String>> consumer = new AtomicReference<>();
    private final AtomicReference<Thread> consumerThread = new AtomicReference<>();

    public PushkinaAuditService(String bootstrapServers, String consumerGroupId) throws IOException {
        this.bootstrapServers = bootstrapServers;
        this.consumerGroupId = consumerGroupId;
        this.auditLogPath = Files.createTempFile("mediocritas-audit-", ".log");
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        KafkaConsumer<String, String> kafkaConsumer = new KafkaConsumer<>(consumerProperties());
        kafkaConsumer.subscribe(List.of(AUDIT_TOPIC));
        consumer.set(kafkaConsumer);
        consumerThread.set(Thread.ofVirtual()
                .name("mediocritas-audit-consumer-" + consumerGroupId)
                .start(() -> consume(kafkaConsumer)));
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        KafkaConsumer<String, String> kafkaConsumer = consumer.get();
        if (kafkaConsumer != null) {
            kafkaConsumer.wakeup();
        }

        Thread thread = consumerThread.get();
        if (thread != null) {
            try {
                thread.join(Duration.ofSeconds(2));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public List<AuditEvent> listAuditEntries() {
        return new ArrayList<>(events);
    }

    private void consume(KafkaConsumer<String, String> kafkaConsumer) {
        try (kafkaConsumer) {
            while (running.get()) {
                ConsumerRecords<String, String> records = kafkaConsumer.poll(Duration.ofMillis(100));
                records.forEach(record -> addAuditEvent(record.value()));
                if (!records.isEmpty()) {
                    kafkaConsumer.commitSync();
                }
            }
        } catch (WakeupException e) {
            if (running.get()) {
                log.error("Audit consumer was interrupted unexpectedly", e);
            }
        } catch (RuntimeException e) {
            log.error("Audit consumer failed", e);
        } finally {
            running.set(false);
            consumer.compareAndSet(kafkaConsumer, null);
            consumerThread.set(null);
        }
    }

    private void addAuditEvent(String value) {
        try {
            AuditEvent event = AuditCodec.decode(value);
            events.add(event);
            Files.writeString(
                    auditLogPath,
                    AuditCodec.encode(event) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.APPEND
            );
        } catch (IllegalArgumentException e) {
            log.warn("Skipping malformed audit event", e);
        } catch (IOException e) {
            log.error("Failed to save audit event", e);
        }
    }

    private Properties consumerProperties() {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, consumerGroupId);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        properties.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 100);
        return properties;
    }
}
