package company.vk.edu.distrib.compute.mediocritas.leaderelection;

public record Message(MessageType type, int senderId) {

    @Override
    public String toString() {
        return "[" + type + " from node-" + senderId + "]";
    }
}
