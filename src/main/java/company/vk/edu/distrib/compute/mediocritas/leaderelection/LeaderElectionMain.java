package company.vk.edu.distrib.compute.mediocritas.leaderelection;

public class LeaderElectionMain {

    static void main() throws InterruptedException {

        Cluster cluster = new Cluster(5);
        cluster.start();
        cluster.startMonitor();

        Thread.sleep(3_000);

        System.out.println("\n>>> Scenario 2: Forced failure of leader (node-5)");
        cluster.getNode(5).forceDown();

        Thread.sleep(4_000);

        System.out.println("\n>>> Scenario 3: Recovery of node-5");
        cluster.getNode(5).forceUp();

        Thread.sleep(4_000);

        System.out.println("\n>>> Scenario 4: Cascading failures of multiple nodes");
        cluster.getNode(4).forceDown();
        cluster.getNode(3).forceDown();

        Thread.sleep(5_000);

        cluster.getNode(4).forceUp();
        cluster.getNode(3).forceUp();

        Thread.sleep(4_000);

        cluster.stop();
    }
}
