package company.vk.edu.distrib.compute.mediocritas.cluster.proxy;

public record ProxyResponse<T>(int statusCode, T body) {

    public static ProxyResponse<byte[]> ok(byte[] body) {
        return new ProxyResponse<>(200, body);
    }

    public static ProxyResponse<byte[]> notFound() {
        return new ProxyResponse<>(404, new byte[0]);
    }

    public static ProxyResponse<Void> created() {
        return new ProxyResponse<>(201, null);
    }

    public static ProxyResponse<Void> accepted() {
        return new ProxyResponse<>(202, null);
    }

    public static ProxyResponse<Void> serverError() {
        return new ProxyResponse<>(503, null);
    }

    public static <T> ProxyResponse<T> error() {
        return new ProxyResponse<>(503, null);
    }

    public static <T> ProxyResponse<T> of(int statusCode, T body) {
        return new ProxyResponse<>(statusCode, body);
    }
}
