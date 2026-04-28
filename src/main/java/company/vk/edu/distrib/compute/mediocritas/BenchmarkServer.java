package company.vk.edu.distrib.compute.mediocritas;

import company.vk.edu.distrib.compute.KVCluster;
import company.vk.edu.distrib.compute.mediocritas.cluster.PushkinaKVClusterFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Лаунчер для нагрузочного тестирования: поднимает {@link PushkinaKVClusterFactory}-кластер
 * с настраиваемым транспортом (HTTP/gRPC) через system property {@code proxy.client.type}.
 * <p>
 * Внешний клиент всегда работает по HTTP на портах 8080/8081 — это позволяет одному и тому же
 * wrk-сценарию измерять обе реализации внутреннего транспорта.
 */
public final class BenchmarkServer {

    private static final Logger LOG = LoggerFactory.getLogger(BenchmarkServer.class);
    private static final List<Integer> DEFAULT_PORTS = List.of(8080, 8081);

    private BenchmarkServer() {
    }

    public static void main(String[] args) {
        KVCluster cluster = new PushkinaKVClusterFactory().create(DEFAULT_PORTS);
        cluster.start();
        LOG.info(
                "Benchmark cluster started: ports={}, proxy.client.type={}",
                DEFAULT_PORTS,
                System.getProperty("proxy.client.type", "grpc")
        );
        Runtime.getRuntime().addShutdownHook(new Thread(cluster::stop));
    }
}
