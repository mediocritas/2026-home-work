package company.vk.edu.distrib.compute.mediocritas.cluster;

import company.vk.edu.distrib.compute.KVCluster;
import company.vk.edu.distrib.compute.KVService;
import company.vk.edu.distrib.compute.mediocritas.cluster.proxy.GrpcProxyClient;
import company.vk.edu.distrib.compute.mediocritas.cluster.proxy.ProxyClient;
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

    private final List<Node> clusterNodes;
    private final Map<String, KVService> services = new ConcurrentHashMap<>();
    private final List<String> endpoints;

    public PushkinaKVCluster(List<Node> clusterNodes, Router router) {
        this.clusterNodes = List.copyOf(clusterNodes);

        ProxyClient proxyClient = new GrpcProxyClient();

        this.clusterNodes.forEach(router::addNode);

        this.endpoints = this.clusterNodes.stream()
                .map(Node::httpEndpoint)
                .collect(Collectors.toList());

        for (Node node : this.clusterNodes) {
            services.put(node.httpEndpoint(), createService(node, router, proxyClient));
        }
    }

    private static KVService createService(Node node, Router router, ProxyClient proxyClient) {
        try {
            String dataPath = "./data-cluster-" + node.httpPort();
            return new ClusterKvByteService(node, new FileByteDao(dataPath), router, proxyClient);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create cluster node on port " + node.httpPort(), e);
        }
    }

    @Override
    public void start() {
        services.values().forEach(KVService::start);
    }

    @Override
    public void start(String endpoint) {
        KVService service = services.get(endpoint);
        if (service != null) {
            service.start();
        }
    }

    @Override
    public void stop() {
        services.values().forEach(KVService::stop);
    }

    @Override
    public void stop(String endpoint) {
        KVService service = services.get(endpoint);
        if (service != null) {
            service.stop();
        }
    }

    @Override
    public List<String> getEndpoints() {
        return endpoints;
    }
}
