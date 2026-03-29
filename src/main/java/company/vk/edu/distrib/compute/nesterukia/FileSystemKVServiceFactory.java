package company.vk.edu.distrib.compute.nesterukia;

import company.vk.edu.distrib.compute.KVService;
import company.vk.edu.distrib.compute.KVServiceFactory;

import java.io.IOException;

public class FileSystemKVServiceFactory extends KVServiceFactory {
    private static final String PERSISTENT_STORAGE_PATH = "storage";

    @Override
    protected KVService doCreate(int port) throws IOException {
        return new KVServiceImpl(
                port,
                new FileSystemKVDao(PERSISTENT_STORAGE_PATH)
        );
    }
}
