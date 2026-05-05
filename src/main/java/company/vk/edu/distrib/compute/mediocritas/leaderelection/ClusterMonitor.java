package company.vk.edu.distrib.compute.mediocritas.leaderelection;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ClusterMonitor implements Runnable {

    private static final Logger LOGGER = Logger.getLogger(ClusterMonitor.class.getName());
    private static final long REFRESH_INTERVAL_MS = 1_000;

    private static final String ANSI_RESET = "\u001B[0m";
    private static final String ANSI_BOLD = "\u001B[1m";
    private static final String ANSI_RED = "\u001B[31m";
    private static final String ANSI_GREEN = "\u001B[32m";
    private static final String ANSI_YELLOW = "\u001B[33m";
    private static final String ANSI_CYAN = "\u001B[36m";
    private static final String ANSI_CLEAR = "\u001B[2J\u001B[H";

    private static final String HEADER =
            "╔══════════════════════════════════════════╗\n"
            + "║     Cluster State Monitor (live)         ║\n"
            + "╚══════════════════════════════════════════╝\n";
    private static final String SEPARATOR = "  ──────────────────────────────────────\n";

    private final List<ClusterNode> nodes;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public ClusterMonitor(List<ClusterNode> nodes) {
        this.nodes = nodes;
    }

    public void stop() {
        running.set(false);
    }

    @Override
    public void run() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            render();
            try {
                Thread.sleep(REFRESH_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void render() {
        int estimatedSize = 512 + nodes.size() * 80;
        StringBuilder sb = new StringBuilder(estimatedSize);
        sb.append(ANSI_CLEAR)
                .append(ANSI_BOLD).append(ANSI_CYAN).append(HEADER).append(ANSI_RESET)
                .append(ANSI_BOLD)
                .append(String.format("  %-8s %-12s %-10s%n", "NODE", "ROLE", "LEADER"))
                .append(ANSI_RESET)
                .append(SEPARATOR);

        for (ClusterNode node : nodes) {
            sb.append(buildNodeLine(node));
        }

        sb.append(SEPARATOR)
                .append(ANSI_CYAN).append("  Refresh every ").append(REFRESH_INTERVAL_MS)
                .append("ms").append(ANSI_RESET).append('\n');

        if (LOGGER.isLoggable(Level.INFO)) {
            LOGGER.info(sb.toString());
        }
    }

    private String buildNodeLine(ClusterNode node) {
        NodeRole role = node.getRole();
        int leaderId = node.getCurrentLeaderId();
        String roleLabel = roleLabel(role);
        String roleColor = roleColor(role);
        String rolePadded = String.format("%-8s", roleLabel);
        String roleColored = roleColor + rolePadded + ANSI_RESET;
        String leaderStr = leaderId < 0 ? "?" : String.valueOf(leaderId);
        return String.format("  node-%-3d ", node.getId())
                + roleColored
                + String.format("  %-10s%n", leaderStr);
    }

    private static String roleLabel(NodeRole role) {
        return switch (role) {
            case LEADER -> "LEADER";
            case FOLLOWER -> "FOLLOWER";
            case DOWN -> "DOWN";
        };
    }

    private static String roleColor(NodeRole role) {
        return switch (role) {
            case LEADER -> ANSI_GREEN + ANSI_BOLD;
            case FOLLOWER -> ANSI_YELLOW;
            case DOWN -> ANSI_RED;
        };
    }
}
