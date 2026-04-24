package company.vk.edu.distrib.compute.mediocritas.service;

import com.sun.net.httpserver.HttpExchange;
import company.vk.edu.distrib.compute.ReplicatedService;
import company.vk.edu.distrib.compute.mediocritas.storage.VersionedFileDao;
import company.vk.edu.distrib.compute.mediocritas.storage.VersionedValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class ReplicatedKVService extends AbstractKvByteService implements ReplicatedService {

    private static final Logger log = LoggerFactory.getLogger(ReplicatedKVService.class);

    private static final int DEFAULT_REPLICATION_FACTOR = 3;
    private static final int DEFAULT_ACK = 1;

    private final int replicationFactor;
    private final List<VersionedFileDao> replicas;
    private final List<AtomicBoolean> replicaStatus;
    private final ExecutorService executor;

    public ReplicatedKVService(int port) {
        this(port, DEFAULT_REPLICATION_FACTOR);
    }

    public ReplicatedKVService(int port, int replicationFactor) {
        super(port, null);
        this.replicationFactor = replicationFactor;
        this.replicas = new ArrayList<>(replicationFactor);
        this.replicaStatus = new ArrayList<>(replicationFactor);
        this.executor = Executors.newFixedThreadPool(replicationFactor);

        for (int i = 0; i < replicationFactor; i++) {
            try {
                replicas.add(new VersionedFileDao("./data-replica-" + port + "-" + i));
            } catch (IOException e) {
                throw new RuntimeException("Failed to initialize replica " + i, e);
            }
            replicaStatus.add(new AtomicBoolean(true));
        }
    }

    @Override
    public int port() {
        return port;
    }

    @Override
    public int numberOfReplicas() {
        return replicationFactor;
    }

    @Override
    public void disableReplica(int nodeId) {
        if (nodeId >= 0 && nodeId < replicationFactor) {
            replicaStatus.get(nodeId).set(false);
        }
    }

    @Override
    public void enableReplica(int nodeId) {
        if (nodeId >= 0 && nodeId < replicationFactor) {
            replicaStatus.get(nodeId).set(true);
        }
    }

    @Override
    protected void handleEntity(HttpExchange http) throws IOException {
        String query = http.getRequestURI().getQuery();
        String id = parseId(query);
        Integer ack = parseAck(query);

        if (ack == null) {
            ack = DEFAULT_ACK;
        }

        if (ack > replicationFactor) {
            http.sendResponseHeaders(400, -1);
            return;
        }

        switch (http.getRequestMethod()) {
            case "GET" -> handleGet(http, id, ack);
            case "PUT" -> handlePut(http, id, ack);
            case "DELETE" -> handleDelete(http, id, ack);
            default -> http.sendResponseHeaders(405, -1);
        }
    }

    private void handleGet(HttpExchange http, String id, int ack) throws IOException {
        List<Integer> activeReplicas = getActiveReplicasForKey(id);

        if (activeReplicas.size() < ack) {
            http.sendResponseHeaders(500, -1);
            return;
        }

        List<CompletableFuture<VersionedValue>> futures = activeReplicas.subList(0, ack).stream()
                .map(replicaId -> CompletableFuture.supplyAsync(
                        () -> replicas.get(replicaId).get(id),
                        executor))
                .toList();

        VersionedValue latestValue = futures.stream()
                .map(CompletableFuture::join)
                .filter(Objects::nonNull)
                .max((a, b) -> Long.compare(a.getTimestamp(), b.getTimestamp()))
                .orElse(null);

        if (latestValue == null || latestValue.isDeleted()) {
            http.sendResponseHeaders(404, -1);
            return;
        }

        byte[] data = latestValue.getData();
        http.sendResponseHeaders(200, data.length);
        sendBody(http, data);
    }

    private void handlePut(HttpExchange http, String id, int ack) throws IOException {
        List<Integer> activeReplicas = getActiveReplicasForKey(id);
        if (activeReplicas.size() < ack) {
            http.sendResponseHeaders(500, -1);
            return;
        }

        byte[] data = http.getRequestBody().readAllBytes();
        VersionedValue value = new VersionedValue(data, System.currentTimeMillis());

        long successCount = executeUpsertOnReplicas(activeReplicas, ack, id, value);
        http.sendResponseHeaders(successCount < ack ? 500 : 201, -1);
    }

    private void handleDelete(HttpExchange http, String id, int ack) throws IOException {
        List<Integer> activeReplicas = getActiveReplicasForKey(id);
        if (activeReplicas.size() < ack) {
            http.sendResponseHeaders(500, -1);
            return;
        }

        VersionedValue deletedValue = VersionedValue.deleted(System.currentTimeMillis());

        long successCount = executeUpsertOnReplicas(activeReplicas, ack, id, deletedValue);
        http.sendResponseHeaders(successCount < ack ? 500 : 202, -1);
    }

    private List<Integer> getActiveReplicasForKey(String id) {
        int startReplica = Math.abs(id.hashCode()) % replicationFactor;

        List<Integer> active = new ArrayList<>();
        for (int i = 0; i < replicationFactor; i++) {
            int replicaId = (startReplica + i) % replicationFactor;
            if (replicaStatus.get(replicaId).get()) {
                active.add(replicaId);
            }
        }
        return active;
    }

    private long executeUpsertOnReplicas(List<Integer> activeReplicas, int ack, String id, VersionedValue value) {
        AtomicInteger successCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(ack);

        activeReplicas.subList(0, ack).stream()
                .forEach(replicaId -> executor.submit(() -> {
                    try {
                        replicas.get(replicaId).upsert(id, value);
                        if (successCount.incrementAndGet() <= ack) {
                            latch.countDown();
                        }
                    } catch (Exception ignored) {}
                }));

        try {
            boolean awaited = latch.await(5, TimeUnit.SECONDS);
            if (!awaited) {
                log.error("Timeout during awaiting ack from replicas");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return successCount.get();
    }
}
