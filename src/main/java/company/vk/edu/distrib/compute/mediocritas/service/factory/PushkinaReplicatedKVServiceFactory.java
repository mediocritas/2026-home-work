package company.vk.edu.distrib.compute.mediocritas.service.factory;

import company.vk.edu.distrib.compute.KVService;
import company.vk.edu.distrib.compute.KVServiceFactory;
import company.vk.edu.distrib.compute.mediocritas.service.ReplicatedKVService;

public class PushkinaReplicatedKVServiceFactory extends KVServiceFactory {
    @Override
    protected KVService doCreate(int port) {
        return new ReplicatedKVService(port);
    }
}
