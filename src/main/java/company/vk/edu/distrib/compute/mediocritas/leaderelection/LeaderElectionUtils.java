package company.vk.edu.distrib.compute.mediocritas.leaderelection;

import java.util.logging.Logger;

public final class LeaderElectionUtils {

    private static final Logger LOGGER = Logger.getLogger(LeaderElectionUtils.class.getName());

    private LeaderElectionUtils() {
    }

    public static void main(String[] args) {
        Cluster cluster = new Cluster(5);
        cluster.start();
        cluster.startMonitor();

        try {
            Thread.sleep(3_000);

            LOGGER.info(">>> Scenario 2: Forced failure of leader (node-5)");
            cluster.getNode(5).forceDown();

            Thread.sleep(4_000);

            LOGGER.info(">>> Scenario 3: Recovery of node-5");
            cluster.getNode(5).forceUp();

            Thread.sleep(4_000);

            LOGGER.info(">>> Scenario 4: Cascading failures of multiple nodes");
            cluster.getNode(4).forceDown();
            cluster.getNode(3).forceDown();

            Thread.sleep(5_000);

            cluster.getNode(4).forceUp();
            cluster.getNode(3).forceUp();

            Thread.sleep(4_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warning("Main thread interrupted");
        } finally {
            cluster.stop();
        }
    }
}
