package company.vk.edu.distrib.compute.mediocritas.cluster.routing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public abstract class AbstractHashRouter implements Router {
    private static final ThreadLocal<MessageDigest> MD5_THREAD_LOCAL = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 not available", e);
        }
    });

    protected long hash(String key) {
        MessageDigest md5 = MD5_THREAD_LOCAL.get();
        md5.reset();
        byte[] digest = md5.digest(key.getBytes(StandardCharsets.UTF_8));

        long hash = 0;
        for (int i = 0; i < 8; i++) {
            hash = (hash << 8) | (digest[i] & 0xFF);
        }
        return hash;
    }
}
