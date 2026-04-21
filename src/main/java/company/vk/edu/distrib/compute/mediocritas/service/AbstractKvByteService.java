package company.vk.edu.distrib.compute.mediocritas.service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import company.vk.edu.distrib.compute.Dao;
import company.vk.edu.distrib.compute.KVService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.util.NoSuchElementException;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public abstract class AbstractKvByteService implements KVService {

    private static final Logger log = LoggerFactory.getLogger(AbstractKvByteService.class);
    private static final int DELAY_TO_STOP_SECONDS = 2;

    private HttpServer httpServer;
    protected final Dao<byte[]> dao;
    protected final int port;
    private boolean isStarted;
    private final Lock lock = new ReentrantLock();

    protected AbstractKvByteService(int port, Dao<byte[]> dao) {
        this.port = port;
        this.dao = dao;
    }

    @Override
    public void start() {
        lock.lock();
        try {
            if (isStarted) {
                log.error("Server already started");
                return;
            }
            httpServer = HttpServer.create(new InetSocketAddress(port), 0);
            registerHandlers();
            httpServer.start();
            isStarted = true;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to start server", e);
        } finally {
            lock.unlock();
        }

        if (log.isInfoEnabled()) {
            log.info("Server started on port {}", port);
        }
    }

    @Override
    public void stop() {
        lock.lock();
        try {
            if (!isStarted) {
                log.error("Trying to stop server which was not started");
            }
            isStarted = false;
            httpServer.stop(DELAY_TO_STOP_SECONDS);
        } finally {
            lock.unlock();
        }
        if (log.isInfoEnabled()) {
            log.info("Server stopped");
        }
    }

    private void registerHandlers() {
        httpServer.createContext("/v0/status", wrap(this::handleStatus));
        httpServer.createContext("/v0/entity", wrap(this::handleEntity));
        httpServer.createContext("/", wrap(http -> http.sendResponseHeaders(404, -1)));
    }

    private void handleStatus(HttpExchange http) throws IOException {
        int status = "GET".equalsIgnoreCase(http.getRequestMethod()) ? 200 : 405;
        http.sendResponseHeaders(status, -1);
    }

    protected abstract void handleEntity(HttpExchange http) throws IOException;

    protected void handleEntityLocally(HttpExchange http, String id) throws IOException {
        switch (http.getRequestMethod()) {
            case "GET" -> {
                byte[] data = dao.get(id);
                http.sendResponseHeaders(200, data.length);
                sendBody(http, data);
            }
            case "PUT" -> {
                dao.upsert(id, http.getRequestBody().readAllBytes());
                http.sendResponseHeaders(201, -1);
            }
            case "DELETE" -> {
                dao.delete(id);
                http.sendResponseHeaders(202, -1);
            }
            default -> http.sendResponseHeaders(405, -1);
        }
    }

    protected void sendBody(HttpExchange http, byte[] data) throws IOException {
        try (var os = http.getResponseBody()) {
            os.write(data);
        }
    }

    private HttpHandler wrap(HttpHandler handler) {
        return http -> {
            try (http) {
                try {
                    handler.handle(http);
                } catch (NoSuchElementException e) {
                    sendStatus(http, 404);
                } catch (IllegalArgumentException e) {
                    sendStatus(http, 400);
                } catch (Exception e) {
                    sendStatus(http, 503);
                }
            }
        };
    }

    private void sendStatus(HttpExchange http, int status) {
        try {
            http.sendResponseHeaders(status, -1);
        } catch (IOException e) {
            log.error("Failed to send status {}", status, e);
        }
    }

    protected String parseId(String query) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Query is empty. Expected 'id='");
        }
        if (query.contains("&")) {
            throw new IllegalArgumentException("Only one query parameter 'id' is allowed");
        }
        String[] parts = query.split("=", 2);
        if (parts.length != 2 || !"id".equals(parts[0])) {
            throw new IllegalArgumentException("Invalid query format. Expected 'id=<value>'");
        }
        String id = parts[1];
        if (id.isBlank()) {
            throw new IllegalArgumentException("Parameter 'id' cannot be empty");
        }
        return id;
    }
}
