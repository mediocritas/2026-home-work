package company.vk.edu.distrib.compute.mediocritas.cluster;

import company.vk.edu.distrib.compute.KVCluster;
import company.vk.edu.distrib.compute.KVService;
import company.vk.edu.distrib.compute.mediocritas.cluster.proxy.HttpProxyClient;
import company.vk.edu.distrib.compute.mediocritas.cluster.routing.Router;
import company.vk.edu.distrib.compute.mediocritas.service.ClusterKvByteService;
import company.vk.edu.distrib.compute.mediocritas.storage.FileByteDao;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class PushkinaKVCluster implements KVCluster {

    private final Map<String, KVService> nodes = new ConcurrentHashMap<>();
    private final List<String> endpoints;

    public PushkinaKVCluster(List<Integer> ports, Router router) {
        HttpProxyClient proxyClient = new HttpProxyClient();

        this.endpoints = ports.stream()
                .map(port -> "http://localhost:" + port)
                .collect(Collectors.toList());

        endpoints.forEach(router::addNode);

        ports.stream()
                .collect(Collectors.toMap(
                        port -> "http://localhost:" + port,
                        port -> createClusterNode(port, router, proxyClient)
                ))
                .forEach(nodes::put);
    }

    private static KVService createClusterNode(int port, Router router, HttpProxyClient proxyClient) {
        try {
            String dataPath = "./data-cluster-" + port;
            return new ClusterKvByteService(port, new FileByteDao(dataPath), router, proxyClient);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create cluster node on port " + port, e);
        }
    }

    @Override
    public void start() {
        for (KVService service : nodes.values()) {
            service.start();
        }
    }

    @Override
    public void start(String endpoint) {
        KVService service = nodes.get(endpoint);
        if (service != null) {
            service.start();
        }
    }

    @Override
    public void stop() {
        for (KVService service : nodes.values()) {
            service.stop();
        }
    }

    @Override
    public void stop(String endpoint) {
        KVService service = nodes.get(endpoint);
        if (service != null) {
            service.stop();
        }
    }

    @Override
    public List<String> getEndpoints() {
        return endpoints;
    }
}
