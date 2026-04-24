package company.vk.edu.distrib.compute.mediocritas.service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import company.vk.edu.distrib.compute.mediocritas.storage.VersionedFileDao;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class ReplicaStats {

    private static final String PATH_PREFIX = "/stats/replica/";
    private static final String GET_METHOD = "GET";
    private static final String ACCESS_SUBPATH = "access";
    private static final int SINGLE_SEGMENT = 1;

    private final int replicationFactor;
    private final List<VersionedFileDao> replicas;
    private final List<AtomicBoolean> replicaStatus;
    private final List<AtomicLong> readCounts;
    private final List<AtomicLong> writeCounts;
    private final long startTimeMillis;

    public ReplicaStats(int replicationFactor,
                        List<VersionedFileDao> replicas,
                        List<AtomicBoolean> replicaStatus) {
        this.replicationFactor = replicationFactor;
        this.replicas = replicas;
        this.replicaStatus = replicaStatus;
        this.readCounts = new ArrayList<>(replicationFactor);
        this.writeCounts = new ArrayList<>(replicationFactor);
        for (int i = 0; i < replicationFactor; i++) {
            readCounts.add(new AtomicLong(0));
            writeCounts.add(new AtomicLong(0));
        }
        this.startTimeMillis = System.currentTimeMillis();
    }

    public void recordRead(int replicaId) {
        readCounts.get(replicaId).incrementAndGet();
    }

    public void recordWrite(int replicaId) {
        writeCounts.get(replicaId).incrementAndGet();
    }

    public String pathPrefix() {
        return PATH_PREFIX;
    }

    public HttpHandler asHttpHandler() {
        return this::handle;
    }

    private void handle(HttpExchange http) throws IOException {
        if (!GET_METHOD.equalsIgnoreCase(http.getRequestMethod())) {
            http.sendResponseHeaders(405, -1);
            return;
        }

        String tail = http.getRequestURI().getPath().substring(PATH_PREFIX.length());
        if (tail.isBlank()) {
            http.sendResponseHeaders(404, -1);
            return;
        }

        String[] parts = tail.split("/", 2);
        int replicaId;
        try {
            replicaId = Integer.parseInt(parts[0]);
        } catch (NumberFormatException e) {
            http.sendResponseHeaders(400, -1);
            return;
        }
        if (replicaId < 0 || replicaId >= replicationFactor) {
            http.sendResponseHeaders(404, -1);
            return;
        }

        String json;
        if (parts.length == SINGLE_SEGMENT) {
            json = buildReplicaStats(replicaId);
        } else if (ACCESS_SUBPATH.equals(parts[1])) {
            json = buildAccessStats(replicaId);
        } else {
            http.sendResponseHeaders(404, -1);
            return;
        }

        sendJson(http, json);
    }

    private String buildReplicaStats(int replicaId) {
        int keyCount = replicas.get(replicaId).keyCount();
        boolean active = replicaStatus.get(replicaId).get();
        return String.format(
                Locale.ROOT,
                "{\"replicaId\":%d,\"keyCount\":%d,\"active\":%s}",
                replicaId, keyCount, active);
    }

    private String buildAccessStats(int replicaId) {
        long reads = readCounts.get(replicaId).get();
        long writes = writeCounts.get(replicaId).get();
        long uptimeMs = Math.max(1, System.currentTimeMillis() - startTimeMillis);
        double uptimeSec = uptimeMs / 1000.0;
        double readsPerSec = reads / uptimeSec;
        double writesPerSec = writes / uptimeSec;
        return String.format(
                "{\"replicaId\":%d,\"reads\":%d,\"writes\":%d,"
                        + "\"uptimeSeconds\":%.3f,\"readsPerSec\":%.3f,\"writesPerSec\":%.3f}",
                replicaId, reads, writes, uptimeSec, readsPerSec, writesPerSec);
    }

    private void sendJson(HttpExchange http, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        http.getResponseHeaders().add("Content-Type", "application/json");
        http.sendResponseHeaders(200, bytes.length);
        try (var os = http.getResponseBody()) {
            os.write(bytes);
        }
    }
}
