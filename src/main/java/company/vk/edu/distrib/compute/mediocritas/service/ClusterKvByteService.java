package company.vk.edu.distrib.compute.mediocritas.service;

import com.google.protobuf.ByteString;
import com.sun.net.httpserver.HttpExchange;
import company.vk.edu.distrib.compute.Dao;
import company.vk.edu.distrib.compute.mediocritas.DeleteRequest;
import company.vk.edu.distrib.compute.mediocritas.DeleteResponse;
import company.vk.edu.distrib.compute.mediocritas.GetRequest;
import company.vk.edu.distrib.compute.mediocritas.GetResponse;
import company.vk.edu.distrib.compute.mediocritas.KvServiceGrpc;
import company.vk.edu.distrib.compute.mediocritas.PutRequest;
import company.vk.edu.distrib.compute.mediocritas.PutResponse;
import company.vk.edu.distrib.compute.mediocritas.cluster.Node;
import company.vk.edu.distrib.compute.mediocritas.cluster.proxy.ProxyClient;
import company.vk.edu.distrib.compute.mediocritas.cluster.proxy.ProxyResponse;
import company.vk.edu.distrib.compute.mediocritas.cluster.routing.Router;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class ClusterKvByteService extends AbstractKvByteService {

    private static final Logger log = LoggerFactory.getLogger(ClusterKvByteService.class);
    private static final long PROXY_TIMEOUT_SECONDS = 10;

    protected final Node localNode;
    protected final Router router;
    protected final ProxyClient proxyClient;

    private final Server grpcServer;

    public ClusterKvByteService(
            Node localNode,
            Dao<byte[]> dao,
            Router router,
            ProxyClient proxyClient
    ) {
        super(localNode.httpPort(), dao);
        this.localNode = localNode;
        this.router = router;
        this.proxyClient = proxyClient;
        this.grpcServer = ServerBuilder
                .forPort(localNode.grpcPort())
                .addService(new KvServiceImpl())
                .build();
    }

    @Override
    public void start() {
        super.start();
        try {
            grpcServer.start();
            if (log.isInfoEnabled()) {
                log.info("gRPC server started on port {}", localNode.grpcPort());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to start gRPC server", e);
        }
    }

    @Override
    public void stop() {
        grpcServer.shutdown();
        try {
            if (!grpcServer.awaitTermination(1, TimeUnit.SECONDS)) {
                grpcServer.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            grpcServer.shutdownNow();
        }
        super.stop();
    }

    @Override
    protected void handleEntity(HttpExchange http) throws IOException {
        String id = parseId(http.getRequestURI().getQuery());
        Node ownerNode = router.getNodeForKey(id);
        if (!ownerNode.equals(localNode)) {
            sendProxyRequest(http, ownerNode, id);
            return;
        }
        handleEntityLocally(http, id);
    }

    private void sendProxyRequest(HttpExchange http, Node targetNode, String key) throws IOException {
        try {
            switch (http.getRequestMethod()) {
                case "GET" -> {
                    ProxyResponse<byte[]> response = await(proxyClient.proxyGet(targetNode, key));
                    http.sendResponseHeaders(response.statusCode(), response.body().length);
                    sendBody(http, response.body());
                }
                case "PUT" -> {
                    byte[] body = http.getRequestBody().readAllBytes();
                    ProxyResponse<Void> response = await(proxyClient.proxyPut(targetNode, key, body));
                    http.sendResponseHeaders(response.statusCode(), -1);
                }
                case "DELETE" -> {
                    ProxyResponse<Void> response = await(proxyClient.proxyDelete(targetNode, key));
                    http.sendResponseHeaders(response.statusCode(), -1);
                }
                default -> http.sendResponseHeaders(405, -1);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Proxy request interrupted", e);
        }
    }

    private static <T> ProxyResponse<T> await(CompletableFuture<ProxyResponse<T>> future)
            throws InterruptedException {
        try {
            return future.get(PROXY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException e) {
            return ProxyResponse.error();
        }
    }

    private final class KvServiceImpl extends KvServiceGrpc.KvServiceImplBase {

        @Override
        public void get(GetRequest request, StreamObserver<GetResponse> obs) {
            try {
                byte[] value = dao.get(request.getKey());
                obs.onNext(GetResponse.newBuilder()
                        .setValue(ByteString.copyFrom(value))
                        .setFound(true)
                        .build());
                obs.onCompleted();
            } catch (NoSuchElementException e) {
                obs.onNext(GetResponse.newBuilder().setFound(false).build());
                obs.onCompleted();
            } catch (Exception e) {
                obs.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        @Override
        public void put(PutRequest request, StreamObserver<PutResponse> obs) {
            try {
                dao.upsert(request.getKey(), request.getValue().toByteArray());
                obs.onNext(PutResponse.newBuilder().setSuccess(true).build());
            } catch (Exception e) {
                obs.onNext(PutResponse.newBuilder().setSuccess(false).build());
            }
            obs.onCompleted();
        }

        @Override
        public void delete(DeleteRequest request, StreamObserver<DeleteResponse> obs) {
            try {
                dao.delete(request.getKey());
                obs.onNext(DeleteResponse.newBuilder().setSuccess(true).build());
            } catch (Exception e) {
                obs.onNext(DeleteResponse.newBuilder().setSuccess(false).build());
            }
            obs.onCompleted();
        }
    }
}
