package company.vk.edu.distrib.compute.mediocritas.cluster.routing;

import java.util.List;

public interface Router {

    void addNode(String endpoint);

    void removeNode(String endpoint);

    String getNodeForKey(String key);

    List<String> getAllNodes();
}
