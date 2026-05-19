package company.vk.edu.distrib.compute.mediocritas.audit;

import company.vk.edu.distrib.compute.AuditService;
import company.vk.edu.distrib.compute.AuditServiceFactory;

import java.io.IOException;

public class PushkinaAuditServiceFactoryImpl extends AuditServiceFactory {

    @Override
    protected AuditService doCreate(String bootstrapServers, String consumerGroupId) throws IOException {
        return new PushkinaAuditService(bootstrapServers, consumerGroupId);
    }
}
