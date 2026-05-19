package company.vk.edu.distrib.compute.mediocritas.service;

import company.vk.edu.distrib.compute.AuditableKVService;
import company.vk.edu.distrib.compute.Dao;
import company.vk.edu.distrib.compute.mediocritas.audit.PushkinaAuditProducer;

import java.io.IOException;

public class AuditableKvByteService extends KvByteService implements AuditableKVService {

    private final PushkinaAuditProducer auditProducer;

    public AuditableKvByteService(int port, Dao<byte[]> dao) throws IOException {
        super(port, dao);
        this.auditProducer = new PushkinaAuditProducer();
    }

    @Override
    public void start() {
        auditProducer.start();
        super.start();
    }

    @Override
    public void stop() {
        super.stop();
        auditProducer.stop();
    }

    @Override
    public void setBootstrapServers(String bootstrapServers) {
        auditProducer.setBootstrapServers(bootstrapServers);
    }

    @Override
    public void setAsync(boolean enabled) {
        auditProducer.setAsync(enabled);
    }

    @Override
    protected void beforeEntityRequest(String method, String id) throws IOException {
        auditProducer.send(method, id, System.currentTimeMillis());
    }
}
