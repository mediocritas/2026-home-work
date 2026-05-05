package company.vk.edu.distrib.compute.mediocritas.leaderelection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Cluster {

    private final List<ClusterNode> nodes = new ArrayList<>();
    private final List<Thread> threads = new ArrayList<>();
    private ClusterMonitor monitor;
    private Thread monitorThread;

    public Cluster(int n) {
        Map<Integer, ClusterNode> peers = new ConcurrentHashMap<>();

        for (int i = 1; i <= n; i++) {
            ClusterNode node = new ClusterNode(i);
            nodes.add(node);
            peers.put(i, node);
        }

        for (ClusterNode node : nodes) {
            node.setPeers(peers);
        }
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
        System.out.println("=== Cluster Status ===");
        for (ClusterNode n : nodes) {
            System.out.printf("  node-%-2d | %-8s | leader=%-3s%n",
                    n.getId(), n.getRole(),
                    n.getCurrentLeaderId() < 0 ? "?" : n.getCurrentLeaderId());
        }
        System.out.println("======================");
    }
}
