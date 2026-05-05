package company.vk.edu.distrib.compute.mediocritas.leaderelection;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ClusterNode implements Runnable {

    private static final Logger LOGGER = Logger.getLogger(ClusterNode.class.getName());

    private static final long PING_INTERVAL_MS = 1_500;
    private static final long ELECTION_TIMEOUT_MS = 1_000;
    private static final long PING_TIMEOUT_MS = 800;
    private static final double FAILURE_PROBABILITY = 0.05;
    private static final long RECOVERY_TIME_MS = 5_000;

    private final int id;
    private final AtomicReference<NodeRole> role = new AtomicReference<>(NodeRole.FOLLOWER);
    private final AtomicInteger currentLeaderId = new AtomicInteger(-1);
    private final AtomicBoolean electionInProgress = new AtomicBoolean(false);
    private final AtomicBoolean forcedDown = new AtomicBoolean(false);
    private final BlockingQueue<Message> inbox = new LinkedBlockingQueue<>();
    private final AtomicReference<Map<Integer, ClusterNode>> peersRef = new AtomicReference<>();
    private final Random random = new Random();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    public ClusterNode(int id) {
        this.id = id;
    }

    public void setPeers(Map<Integer, ClusterNode> peers) {
        peersRef.set(peers);
    }

    @Override
    public void run() {
        log("started, role: " + role.get());

        scheduler.scheduleAtFixedRate(this::randomFailureCheck, 3, 3, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(
                this::pingLeaderIfFollower, PING_INTERVAL_MS, PING_INTERVAL_MS, TimeUnit.MILLISECONDS);

        startElection();

        while (!Thread.currentThread().isInterrupted()) {
            try {
                Message msg = inbox.poll(200, TimeUnit.MILLISECONDS);
                if (msg != null) {
                    handleMessage(msg);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        scheduler.shutdownNow();
        log("stopped");
    }

    private void handleMessage(Message msg) {
        if (isDown()) {
            return;
        }

        switch (msg.type()) {
            case PING -> handlePing(msg);
            case ELECT -> handleElect(msg);
            case ANSWER -> { }
            case VICTORY -> handleVictory(msg);
        }
    }

    private void handlePing(Message msg) {
        if (role.get() == NodeRole.LEADER) {
            send(msg.senderId(), new Message(MessageType.ANSWER, id));
        }
    }

    private void handleElect(Message msg) {
        send(msg.senderId(), new Message(MessageType.ANSWER, id));
        if (!electionInProgress.get()) {
            startElection();
        }
    }

    private void handleVictory(Message msg) {
        int newLeader = msg.senderId();
        currentLeaderId.set(newLeader);
        if (id == newLeader) {
            role.set(NodeRole.LEADER);
            log("I am the new LEADER (ID=" + id + ")");
        } else {
            role.set(NodeRole.FOLLOWER);
            log("new leader fixed: node-" + newLeader);
        }
        electionInProgress.set(false);
    }

    private void startElection() {
        if (isDown()) {
            return;
        }
        if (!electionInProgress.compareAndSet(false, true)) {
            return;
        }

        log("starting election (ELECT -> nodes with higher ID)");

        Map<Integer, ClusterNode> peers = peersRef.get();
        List<Integer> higherIds = peers.keySet().stream()
                .filter(pid -> pid > id)
                .toList();

        if (higherIds.isEmpty()) {
            declareVictory();
            return;
        }

        AtomicBoolean answered = new AtomicBoolean(false);

        Message electMsg = new Message(MessageType.ELECT, id);
        for (int pid : higherIds) {
            ClusterNode target = peers.get(pid);
            if (target != null) {
                target.receive(electMsg);
                scheduler.schedule(() -> {
                    if (!target.isDown()) {
                        answered.set(true);
                    }
                }, PING_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            }
        }

        scheduler.schedule(() -> {
            if (!answered.get()) {
                declareVictory();
            }
        }, ELECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private void declareVictory() {
        if (isDown()) {
            return;
        }
        log("VICTORY! Declaring myself leader -> broadcasting to all");
        role.set(NodeRole.LEADER);
        currentLeaderId.set(id);
        electionInProgress.set(false);

        Map<Integer, ClusterNode> peers = peersRef.get();
        Message victoryMsg = new Message(MessageType.VICTORY, id);
        for (int pid : peers.keySet()) {
            if (pid != id) {
                send(pid, victoryMsg);
            }
        }
    }

    private void pingLeaderIfFollower() {
        if (isDown() || role.get() != NodeRole.FOLLOWER) {
            return;
        }

        int leaderId = currentLeaderId.get();
        if (leaderId < 0) {
            startElection();
            return;
        }

        ClusterNode leader = peersRef.get().get(leaderId);
        if (leader == null || leader.isDown()) {
            log("leader node-" + leaderId + " unavailable -> starting election");
            currentLeaderId.set(-1);
            startElection();
            return;
        }

        leader.receive(new Message(MessageType.PING, id));

        scheduler.schedule(() -> {
            if (leader.isDown()) {
                log("leader node-" + leaderId + " did not respond to PING -> election");
                currentLeaderId.set(-1);
                startElection();
            }
        }, PING_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private void randomFailureCheck() {
        if (forcedDown.get()) {
            return;
        }
        if (!isDown() && random.nextDouble() < FAILURE_PROBABILITY) {
            simulateFailure();
            scheduler.schedule(this::simulateRecovery, RECOVERY_TIME_MS, TimeUnit.MILLISECONDS);
        }
    }

    public void simulateFailure() {
        if (role.compareAndSet(NodeRole.LEADER, NodeRole.DOWN)
                || role.compareAndSet(NodeRole.FOLLOWER, NodeRole.DOWN)) {
            log("!!! NODE FAILURE !!!");
            currentLeaderId.set(-1);
            electionInProgress.set(false);
            inbox.clear();
        }
    }

    public void simulateRecovery() {
        if (role.compareAndSet(NodeRole.DOWN, NodeRole.FOLLOWER)) {
            log("recovered -> starting election");
            currentLeaderId.set(-1);
            electionInProgress.set(false);
            startElection();
        }
    }

    public void forceDown() {
        forcedDown.set(true);
        simulateFailure();
    }

    public void forceUp() {
        forcedDown.set(false);
        simulateRecovery();
    }

    private void send(int targetId, Message msg) {
        ClusterNode target = peersRef.get().get(targetId);
        if (target != null) {
            target.receive(msg);
        }
    }

    public void receive(Message msg) {
        if (!isDown()) {
            inbox.offer(msg);
        }
    }

    public boolean isDown() {
        return role.get() == NodeRole.DOWN;
    }

    public int getId() {
        return id;
    }

    public NodeRole getRole() {
        return role.get();
    }

    public int getCurrentLeaderId() {
        return currentLeaderId.get();
    }

    private void log(String msg) {
        if (LOGGER.isLoggable(Level.INFO)) {
            LOGGER.info(String.format("[node-%d | %-8s | leader=%-3s] %s",
                    id, role.get(), currentLeaderId.get() < 0 ? "?" : currentLeaderId.get(), msg));
        }
    }
}
