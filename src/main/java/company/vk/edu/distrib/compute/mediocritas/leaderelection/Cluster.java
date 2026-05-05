package company.vk.edu.distrib.compute.mediocritas.leaderelection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.IntStream;

public class Cluster {

    private static final Logger LOGGER = Logger.getLogger(Cluster.class.getName());

    private final List<ClusterNode> nodes;
    private final List<Thread> threads = new ArrayList<>();
    private ClusterMonitor monitor;
    private Thread monitorThread;

    public Cluster(int n) {
        List<ClusterNode> created = IntStream.rangeClosed(1, n)
                .mapToObj(ClusterNode::new)
                .toList();

        Map<Integer, ClusterNode> peers = new ConcurrentHashMap<>();
        for (ClusterNode node : created) {
            peers.put(node.getId(), node);
        }

        for (ClusterNode node : created) {
            node.setPeers(peers);
        }

        this.nodes = new ArrayList<>(created);
    }

    public void start() {
        for (ClusterNode node : nodes) {
            Thread t = Thread.ofVirtual()
                    .name("cluster-node-" + node.getId())
                    .start(node);
            threads.add(t);
        }
    }

    public void stop() {
        if (monitor != null) {
            monitor.stop();
        }
        threads.forEach(Thread::interrupt);
    }

    public void startMonitor() {
        monitor = new ClusterMonitor(List.copyOf(nodes));
        monitorThread = Thread.ofVirtual()
                .name("cluster-monitor")
                .start(monitor);
    }

    public void stopMonitor() {
        if (monitor != null) {
            monitor.stop();
            monitorThread.interrupt();
        }
    }

    public ClusterNode getNode(int id) {
        return nodes.stream()
                .filter(n -> n.getId() == id)
                .findFirst()
                .orElseThrow();
    }

    public List<ClusterNode> getNodes() {
        return List.copyOf(nodes);
    }

    public void printStatus() {
        if (LOGGER.isLoggable(Level.INFO)) {
            StringBuilder sb = new StringBuilder(256);
            sb.append("=== Cluster Status ===\n");
            for (ClusterNode n : nodes) {
                sb.append(String.format("  node-%-2d | %-8s | leader=%-3s%n",
                        n.getId(), n.getRole(),
                        n.getCurrentLeaderId() < 0 ? "?" : n.getCurrentLeaderId()));
            }
            sb.append("======================");
            LOGGER.info(sb.toString());
        }
    }
}
