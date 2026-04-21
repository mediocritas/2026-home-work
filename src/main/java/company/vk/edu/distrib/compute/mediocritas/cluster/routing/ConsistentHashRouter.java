package company.vk.edu.distrib.compute.mediocritas.cluster.routing;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

public class ConsistentHashRouter extends AbstractHashRouter {
    private final NavigableMap<Long, String> ring = new ConcurrentSkipListMap<>();
    private final Set<String> uniqueNodes = ConcurrentHashMap.newKeySet();
    private final int virtualNodesCount;

    public ConsistentHashRouter(int virtualNodesCount) {
        this.virtualNodesCount = virtualNodesCount;
    }

    @Override
    public void addNode(String endpoint) {
        uniqueNodes.add(endpoint);
        for (int i = 0; i < virtualNodesCount; i++) {
            String virtualNodeKey = endpoint + "#" + i;
            long hash = hash(virtualNodeKey);
            ring.put(hash, endpoint);
        }
    }

    @Override
    public void removeNode(String endpoint) {
        uniqueNodes.remove(endpoint);
        for (int i = 0; i < virtualNodesCount; i++) {
            String virtualNodeKey = endpoint + "#" + i;
            long hash = hash(virtualNodeKey);
            ring.remove(hash);
        }
    }

    @Override
    public String getNodeForKey(String key) {
        if (ring.isEmpty()) {
            throw new IllegalStateException("No nodes in the ring");
        }

        long hash = hash(key);
        Map.Entry<Long, String> entry = ring.ceilingEntry(hash);

        if (entry == null) {
            entry = ring.firstEntry();
        }

        return entry.getValue();
    }

    @Override
    public List<String> getAllNodes() {
        return new ArrayList<>(uniqueNodes);
    }
}
