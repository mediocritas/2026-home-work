package company.vk.edu.distrib.compute.mediocritas.storage;

import java.io.Serializable;
import java.util.Arrays;

public class VersionedValue implements Serializable {
    private final byte[] data;
    private final long timestamp;
    private final boolean deleted;

    public VersionedValue(byte[] data, long timestamp) {
        this(data, timestamp, false);
    }

    public VersionedValue(byte[] data, long timestamp, boolean deleted) {
        this.data = data;
        this.timestamp = timestamp;
        this.deleted = deleted;
    }

    public byte[] getData() {
        return data;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public static VersionedValue deleted(long timestamp) {
        return new VersionedValue(null, timestamp, true);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        VersionedValue that = (VersionedValue) o;
        return timestamp == that.timestamp && deleted == that.deleted && Arrays.equals(data, that.data);
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(data);
        result = 31 * result + Long.hashCode(timestamp);
        result = 31 * result + Boolean.hashCode(deleted);
        return result;
    }
}
