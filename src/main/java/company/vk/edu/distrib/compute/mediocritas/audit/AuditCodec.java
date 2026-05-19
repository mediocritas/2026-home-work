package company.vk.edu.distrib.compute.mediocritas.audit;

import company.vk.edu.distrib.compute.AuditEvent;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class AuditCodec {

    private static final String SEPARATOR = "\t";
    private static final int AUDIT_EVENT_PARTS = 3;
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private AuditCodec() {
    }

    public static String encode(AuditEvent event) {
        return event.timestamp()
                + SEPARATOR
                + encodeField(event.method())
                + SEPARATOR
                + encodeField(event.id());
    }

    public static AuditEvent decode(String value) {
        String[] parts = value.split(SEPARATOR, -1);
        if (parts.length != AUDIT_EVENT_PARTS) {
            throw new IllegalArgumentException("Invalid audit event format");
        }
        long timestamp = Long.parseLong(parts[0]);
        return new AuditEvent(decodeField(parts[1]), decodeField(parts[2]), timestamp);
    }

    private static String encodeField(String value) {
        return ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeField(String value) {
        return new String(DECODER.decode(value), StandardCharsets.UTF_8);
    }
}
