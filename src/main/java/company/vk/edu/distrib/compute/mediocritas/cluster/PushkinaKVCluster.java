package company.vk.edu.distrib.compute.mediocritas.cluster;

import company.vk.edu.distrib.compute.KVCluster;
import company.vk.edu.distrib.compute.KVService;
import company.vk.edu.distrib.compute.mediocritas.cluster.proxy.GrpcProxyClient;
import company.vk.edu.distrib.compute.mediocritas.cluster.proxy.HttpProxyClient;
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

    private static final String PROXY_CLIENT_TYPE_PROPERTY = "proxy.client.type";
    private static final String GRPC = "grpc";
    private static final String HTTP = "http";

    private final Map<String, KVService> services = new ConcurrentHashMap<>();
    private final List<String> endpoints;

    public PushkinaKVCluster(List<Node> clusterNodes, Router router) {
        List<Node> clusterNodes1 = List.copyOf(clusterNodes);

        ProxyClient proxyClient = createProxyClient();

        clusterNodes1.forEach(router::addNode);

        this.endpoints = clusterNodes1.stream()
                .map(Node::httpEndpoint)
                .collect(Collectors.toList());

        for (Node node : clusterNodes1) {
            services.put(node.httpEndpoint(), createService(node, router, proxyClient));
        }
    }

    private static ProxyClient createProxyClient() {
        String type = System.getProperty(PROXY_CLIENT_TYPE_PROPERTY, GRPC);
        return switch (type) {
            case GRPC -> new GrpcProxyClient();
            case HTTP -> new HttpProxyClient();
            default -> throw new IllegalArgumentException(
                    "Unknown proxy client type: " + type + ". Supported: " + GRPC + ", " + HTTP
            );
        };
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
